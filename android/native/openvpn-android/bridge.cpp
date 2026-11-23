#include <jni.h>
#include <client/ovpncli.hpp>
#include <openvpn/common/version.hpp>
#include <mutex>
#include <map>
#include <memory>
#include <atomic>

namespace {
JavaVM* vm;
struct Env {
    JNIEnv* env = nullptr;
    bool attached = false;
    Env() {
        if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
            attached = vm->AttachCurrentThread(&env, nullptr) == JNI_OK;
        }
    }
    ~Env() { if (attached) vm->DetachCurrentThread(); }
};
std::string string(JNIEnv* env, jstring value) {
    if (!value) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}
class Client : public openvpn::ClientAPI::OpenVPNClient {
    jobject callback;
    jmethodID operation, notification;
public:
    Client(JNIEnv* env, jobject object) : callback(env->NewGlobalRef(object)) {
        auto cls = env->GetObjectClass(object);
        operation = env->GetMethodID(cls, "operation", "(ILjava/lang/String;I)I");
        notification = env->GetMethodID(cls, "onEvent", "(Ljava/lang/String;Ljava/lang/String;Z)V");
        env->DeleteLocalRef(cls);
    }
    ~Client() override { Env e; e.env->DeleteGlobalRef(callback); }
    int call(int op, const std::string& text = "", int number = 0) {
        Env e;
        auto value = e.env->NewStringUTF(text.c_str());
        int result = e.env->CallIntMethod(callback, operation, op, value, number);
        e.env->DeleteLocalRef(value);
        if (e.env->ExceptionCheck()) { e.env->ExceptionClear(); return -1; }
        return result;
    }
    void notify(const std::string& name, const std::string& info, bool error) {
        Env e;
        auto n = e.env->NewStringUTF(name.c_str());
        auto i = e.env->NewStringUTF(info.c_str());
        e.env->CallVoidMethod(callback, notification, n, i, error);
        e.env->DeleteLocalRef(n); e.env->DeleteLocalRef(i);
        if (e.env->ExceptionCheck()) e.env->ExceptionClear();
    }
    bool pause_on_connection_timeout() override { return false; }
    bool socket_protect(int fd, std::string, bool) override { return call(1, "", fd) == 1; }
    void event(const openvpn::ClientAPI::Event& event) override { notify(event.name, event.info, event.error); }
    void acc_event(const openvpn::ClientAPI::AppCustomControlMessageEvent&) override {}
    void log(const openvpn::ClientAPI::LogInfo&) override {}
    void external_pki_cert_request(openvpn::ClientAPI::ExternalPKICertRequest& r) override {
        r.error = true; r.errorText = "Import a profile containing its certificate and private key";
    }
    void external_pki_sign_request(openvpn::ClientAPI::ExternalPKISignRequest& r) override {
        r.error = true; r.errorText = "External PKI is not configured";
    }
    bool tun_builder_new() override { return call(2) == 1; }
    bool tun_builder_set_remote_address(const std::string&, bool) override { return true; }
    bool tun_builder_set_layer(int layer) override { return layer == 3; }
    bool tun_builder_add_address(const std::string& a, int prefix, const std::string&, bool, bool) override {
        return call(3, a, prefix) == 1;
    }
    bool tun_builder_add_route(const std::string& a, int prefix, int, bool) override { return call(4, a, prefix) == 1; }
    bool tun_builder_exclude_route(const std::string& a, int prefix, int, bool) override { return call(5, a, prefix) == 1; }
    bool tun_builder_reroute_gw(bool ipv4, bool ipv6, unsigned int) override {
        return (!ipv4 || call(4, "0.0.0.0", 0) == 1) && (!ipv6 || call(4, "::", 0) == 1);
    }
    bool tun_builder_set_dns_options(const openvpn::DnsOptions& options) override {
        for (const auto& [priority, server] : options.servers)
            for (const auto& a : server.addresses)
                if ((a.port != 0 && a.port != 53) || call(6, a.address) != 1) return false;
        for (const auto& d : options.search_domains) if (call(7, d.domain) != 1) return false;
        return true;
    }
    bool tun_builder_set_mtu(int mtu) override { return call(8, "", mtu) == 1; }
    bool tun_builder_set_session_name(const std::string&) override { return true; }
    bool tun_builder_set_allow_family(int af, bool allow) override { return !allow || call(10, "", af) == 1; }
    int tun_builder_establish() override { return call(9); }
};
std::mutex lock;
std::map<jlong, std::shared_ptr<Client>> clients;
std::atomic<jlong> nextId{1};
std::shared_ptr<Client> find(jlong id) { std::lock_guard guard(lock); auto it = clients.find(id); return it == clients.end() ? nullptr : it->second; }
void fail(JNIEnv* env, const std::string& message) { auto cls = env->FindClass("java/lang/IllegalArgumentException"); env->ThrowNew(cls, message.c_str()); }
}
extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM* javaVm, void*) { vm = javaVm; return JNI_VERSION_1_6; }
extern "C" JNIEXPORT jstring JNICALL Java_com_v2ray_ang_tunnel_OpenVpnNative_version(JNIEnv* env, jobject) {
    return env->NewStringUTF(OPENVPN_VERSION);
}
extern "C" JNIEXPORT jlong JNICALL Java_com_v2ray_ang_tunnel_OpenVpnNative_create(JNIEnv* env, jobject, jobject callback, jstring content, jstring user, jstring password) {
    try {
        auto client = std::make_shared<Client>(env, callback);
        openvpn::ClientAPI::Config config;
        config.content = string(env, content); config.guiVersion = "SpicyVPN";
        config.connTimeout = 30; config.tunPersist = false; config.googleDnsFallback = true;
        config.enableRouteEmulation = true;
        auto evaluation = client->eval_config(config);
        if (evaluation.error) { fail(env, evaluation.message); return 0; }
        openvpn::ClientAPI::ProvideCreds creds;
        creds.username = string(env, user); creds.password = string(env, password);
        auto status = client->provide_creds(creds);
        if (status.error) { fail(env, status.message); return 0; }
        auto id = nextId++;
        std::lock_guard guard(lock); clients[id] = client; return id;
    } catch (const std::exception& e) { fail(env, e.what()); return 0; }
}
extern "C" JNIEXPORT jstring JNICALL Java_com_v2ray_ang_tunnel_OpenVpnNative_connect(JNIEnv* env, jobject, jlong id) {
    auto client = find(id); if (!client) return env->NewStringUTF("OpenVPN client is closed");
    try { auto status = client->connect(); return env->NewStringUTF(status.error ? status.message.c_str() : ""); }
    catch (const std::exception& e) { return env->NewStringUTF(e.what()); }
}
extern "C" JNIEXPORT void JNICALL Java_com_v2ray_ang_tunnel_OpenVpnNative_stop(JNIEnv*, jobject, jlong id) { auto client = find(id); if (client) client->stop(); }
extern "C" JNIEXPORT void JNICALL Java_com_v2ray_ang_tunnel_OpenVpnNative_destroy(JNIEnv*, jobject, jlong id) { std::lock_guard guard(lock); clients.erase(id); }
extern "C" JNIEXPORT jlongArray JNICALL Java_com_v2ray_ang_tunnel_OpenVpnNative_traffic(JNIEnv* env, jobject, jlong id) {
    auto client = find(id);
    jlong values[2] = {0, 0};
    if (client) { auto stats = client->transport_stats(); values[0] = stats.bytesIn; values[1] = stats.bytesOut; }
    auto result = env->NewLongArray(2); env->SetLongArrayRegion(result, 0, 2, values); return result;
}
