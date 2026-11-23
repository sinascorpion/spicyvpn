#include <client/ovpncli.hpp>
#include <sys/socket.h>
#include <poll.h>
#include <unistd.h>
#include <atomic>
#include <chrono>
#include <fstream>
#include <iostream>
#include <thread>
#include <vector>

class TestClient : public openvpn::ClientAPI::OpenVPNClient {
public:
    std::atomic<bool> connected{false};
    std::atomic<bool> certificateRejected{false};
    int peer = -1;
    bool pause_on_connection_timeout() override { return false; }
    void log(const openvpn::ClientAPI::LogInfo& info) override {
        std::cerr << info.text;
        if (info.text.find("VERIFY FAIL -- verify-x509-name failed") != std::string::npos)
            certificateRejected = true;
    }
    void event(const openvpn::ClientAPI::Event& e) override {
        std::cerr << e.name << " " << e.info << '\n';
        if (e.name == "CONNECTED") connected = true;
        if (e.name == "CERT_VERIFY_FAIL") certificateRejected = true;
    }
    void acc_event(const openvpn::ClientAPI::AppCustomControlMessageEvent&) override {}
    void external_pki_cert_request(openvpn::ClientAPI::ExternalPKICertRequest& r) override { r.error = true; }
    void external_pki_sign_request(openvpn::ClientAPI::ExternalPKISignRequest& r) override { r.error = true; }
    bool tun_builder_new() override { return true; }
    bool tun_builder_set_remote_address(const std::string&, bool) override { return true; }
    bool tun_builder_add_address(const std::string&, int, const std::string&, bool, bool) override { return true; }
    bool tun_builder_add_route(const std::string&, int, int, bool) override { return true; }
    bool tun_builder_exclude_route(const std::string&, int, int, bool) override { return true; }
    bool tun_builder_set_dns_options(const openvpn::DnsOptions&) override { return true; }
    bool tun_builder_set_mtu(int) override { return true; }
    bool tun_builder_set_session_name(const std::string&) override { return true; }
    bool tun_builder_reroute_gw(bool, bool, unsigned int) override { return true; }
    int tun_builder_establish() override {
        int pair[2];
        if (socketpair(AF_UNIX, SOCK_DGRAM, 0, pair) != 0) return -1;
        peer = pair[1];
        return pair[0];
    }
};

int main(int argc, char** argv) {
    if (argc < 2 || argc > 3) return 2;
    const bool rejectName = argc == 3 && std::string(argv[2]) == "--reject-name";
    std::ifstream input(argv[1]);
    openvpn::ClientAPI::Config config;
    config.content.assign(std::istreambuf_iterator<char>(input), {});
    if (rejectName) config.content += "\nverify-x509-name SpicyVPN-wrong-server name\n";
    config.connTimeout = 15;
    config.tunPersist = false;
    TestClient client;
    auto parsed = client.eval_config(config);
    if (parsed.error) { std::cerr << parsed.message << '\n'; return 3; }
    openvpn::ClientAPI::Status status;
    std::thread connection([&] { status = client.connect(); });
    for (int i = 0; i < 200 && !client.connected && !client.certificateRejected; ++i)
        std::this_thread::sleep_for(std::chrono::milliseconds(100));
    bool passed = false;
    if (client.connected && client.peer >= 0) {
        const std::string payload = "SpicyVPN-OpenVPN-real-packet";
        std::vector<unsigned char> packet(28 + payload.size(), 0);
        packet[0] = 0x45;
        packet[2] = packet.size() >> 8; packet[3] = packet.size();
        packet[8] = 64; packet[9] = 17;
        packet[12] = 10; packet[13] = 8; packet[15] = 2;
        packet[16] = 10; packet[17] = 8; packet[19] = 1;
        unsigned int checksum = 0;
        for (int i = 0; i < 20; i += 2) checksum += (packet[i] << 8) | packet[i + 1];
        while (checksum >> 16) checksum = (checksum & 0xffff) + (checksum >> 16);
        checksum = ~checksum;
        packet[10] = checksum >> 8; packet[11] = checksum;
        packet[20] = 0x9c; packet[21] = 0x40; // client UDP port 40000
        packet[22] = 0x11; packet[23] = 0x5c; // echo UDP port 4444
        packet[24] = (8 + payload.size()) >> 8; packet[25] = 8 + payload.size();
        std::copy(payload.begin(), payload.end(), packet.begin() + 28);
        if (write(client.peer, packet.data(), packet.size()) != static_cast<ssize_t>(packet.size())) {
            client.stop(); connection.join(); close(client.peer); return 4;
        }
        for (int i = 0; i < 20 && !passed; ++i) {
            pollfd fd{client.peer, POLLIN, 0};
            if (poll(&fd, 1, 1000) <= 0) continue;
            unsigned char response[4096];
            auto length = read(client.peer, response, sizeof(response));
            if (length >= 28 && response[9] == 17) {
                auto header = (response[0] & 15) * 4 + 8;
                if (length >= header) passed = std::string(reinterpret_cast<char*>(response + header), length - header) == payload;
            }
        }
    }
    client.stop();
    connection.join();
    if (client.peer >= 0) close(client.peer);
    if (status.error) std::cerr << status.message << '\n';
    if (rejectName) {
        passed = !client.connected && client.certificateRejected;
        std::cout << (passed ? "PASS: Wrong server certificate name rejected\n" : "FAIL: Certificate name verification\n");
        return passed ? 0 : 1;
    }
    std::cout << (passed ? "PASS: OpenVPN handshake, encrypted packet transfer and stop\n" : "FAIL: OpenVPN packet transfer\n");
    return passed ? 0 : 1;
}
