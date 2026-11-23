package tls

import (
    stdtls "crypto/tls"
    "crypto/x509"
    "net/http"
    "net/http/httptest"
    "strings"
    "testing"
)

func TestSpicyCipherMappingKeepsExplicitLegacySuites(t *testing.T) {
    names := []string{
        "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
        "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
        "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
        "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
        "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256",
        "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256",
        "TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA",
        "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA",
        "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256",
        "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256",
    }
    config := (&Config{CipherSuites: strings.Join(names, ":")}).GetTLSConfig()
    if len(config.CipherSuites) != len(names) {
        t.Fatalf("explicit cipher suites were dropped: %v", config.CipherSuites)
    }
    for i, id := range config.CipherSuites {
        if stdtls.CipherSuiteName(id) != names[i] {
            t.Fatalf("cipher %d changed: %s", i, stdtls.CipherSuiteName(id))
        }
    }
}

func TestSpicyCipherLegacyTLS12Handshake(t *testing.T) {
    const suite = stdtls.TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256
    server := httptest.NewUnstartedServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {}))
    server.TLS = &stdtls.Config{
        MinVersion: stdtls.VersionTLS12, MaxVersion: stdtls.VersionTLS12,
        CipherSuites: []uint16{suite},
    }
    server.StartTLS()
    defer server.Close()
    config := (&Config{
        MinVersion: "1.2", MaxVersion: "1.2",
        CipherSuites: "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256",
    }).GetTLSConfig()
    config.RootCAs = x509.NewCertPool()
    config.RootCAs.AddCert(server.Certificate())
    conn, err := stdtls.Dial("tcp", server.Listener.Addr().String(), config)
    if err != nil { t.Fatal(err) }
    defer conn.Close()
    if conn.ConnectionState().CipherSuite != suite { t.Fatal("wrong negotiated cipher") }
}
