package mobile

import (
	"bytes"
	"io"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestRealDnsTunnelTransfer(t *testing.T) {
	serverBinary := os.Getenv("SPICY_DNSTT_SERVER")
	if serverBinary == "" { t.Skip("set SPICY_DNSTT_SERVER to enable the local integration test") }
	dir := t.TempDir()
	priv, pub := filepath.Join(dir, "private.key"), filepath.Join(dir, "public.key")
	if output, err := exec.Command(serverBinary, "-gen-key", "-privkey-file", priv, "-pubkey-file", pub).CombinedOutput(); err != nil {
		t.Fatalf("generate fixture keys: %v: %s", err, output)
	}
	key, err := os.ReadFile(pub)
	if err != nil { t.Fatal(err) }
	backend, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil { t.Fatal(err) }
	defer backend.Close()
	go func() { conn, err := backend.Accept(); if err == nil { defer conn.Close(); io.Copy(conn, conn) } }()
	reservation, err := net.ListenPacket("udp4", "127.0.0.1:0")
	if err != nil { t.Fatal(err) }
	dnsAddress := reservation.LocalAddr().String()
	reservation.Close()
	server := exec.Command(serverBinary, "-privkey-file", priv, "-udp", dnsAddress, "t.example", backend.Addr().String())
	if err := server.Start(); err != nil { t.Fatal(err) }
	defer func() { server.Process.Kill(); server.Wait() }()
	time.Sleep(300 * time.Millisecond)
	listen, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil { t.Fatal(err) }
	listenAddress := listen.Addr().String()
	listen.Close()
	client, err := NewClient(dnsAddress, "t.example", strings.TrimSpace(string(key)), listenAddress)
	if err != nil { t.Fatal(err) }
	if err := client.Start(); err != nil { t.Fatal(err) }
	defer client.Stop()
	conn, err := net.DialTimeout("tcp", listenAddress, time.Second)
	if err != nil { t.Fatal(err) }
	defer conn.Close()
	conn.SetDeadline(time.Now().Add(30 * time.Second))
	payload := bytes.Repeat([]byte("SpicyVPN-DNSTT-transfer\n"), 128)
	if _, err := conn.Write(payload); err != nil { t.Fatal(err) }
	reply := make([]byte, len(payload))
	if _, err := io.ReadFull(conn, reply); err != nil { t.Fatal(err) }
	if !bytes.Equal(reply, payload) { t.Fatal("tunnel corrupted the payload") }
	if !client.IsRunning() { t.Fatal("tunnel closed during transfer") }
	client.Stop()
	if client.IsRunning() { t.Fatal("tunnel remained active after stop") }
}
