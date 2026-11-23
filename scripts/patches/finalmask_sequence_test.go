package fragment

import (
    "bytes"
    "crypto/tls"
    "io"
    "net"
    "net/http"
    "net/http/httptest"
    "testing"
)

type captureConn struct { net.Conn; data bytes.Buffer }
func (c *captureConn) Write(p []byte) (int, error) { return c.data.Write(p) }

func TestZeroLengthRecordAndSequence(t *testing.T) {
    raw := &captureConn{}
    wrapped, err := NewConnClient(&Config{PacketsTo: 1, LengthsMin: []int64{0, 104, 1},
        LengthsMax: []int64{0, 104, 1}, DelaysMin: []int64{0}, DelaysMax: []int64{0}}, raw, false)
    if err != nil { t.Fatal(err) }
    payload := bytes.Repeat([]byte{42}, 108)
    record := append([]byte{22, 3, 1, 0, 108}, payload...)
    n, err := wrapped.Write(record)
    if err != nil || n != len(record) { t.Fatalf("write: %d %v", n, err) }
    wire := raw.data.Bytes()
    var lengths []int
    var restored []byte
    for len(wire) > 0 {
        if len(wire) < 5 { t.Fatal("truncated record") }
        length := int(wire[3])<<8 | int(wire[4])
        if len(wire) < 5+length { t.Fatal("truncated payload") }
        lengths = append(lengths, length)
        restored = append(restored, wire[5:5+length]...)
        wire = wire[5+length:]
    }
    if len(lengths) != 6 || lengths[0] != 0 || lengths[1] != 104 || lengths[2] != 1 {
        t.Fatalf("incorrect TLS sequence: %v", lengths)
    }
    if !bytes.Equal(restored, payload) { t.Fatal("fragmentation changed payload") }
}

func TestHTTPSWithSequenceTLSHello(t *testing.T) {
    server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
        io.WriteString(w, "finalmask-ok")
    }))
    defer server.Close()
    raw, err := net.Dial("tcp", server.Listener.Addr().String())
    if err != nil { t.Fatal(err) }
    defer raw.Close()
    wrapped, err := NewConnClient(&Config{PacketsTo: 1, LengthsMin: []int64{104,1},
        LengthsMax: []int64{104,1}, DelaysMin: []int64{0}, DelaysMax: []int64{0}}, raw, false)
    if err != nil { t.Fatal(err) }
    conn := tls.Client(wrapped, &tls.Config{InsecureSkipVerify: true})
    if err := conn.Handshake(); err != nil { t.Fatal(err) }
    if _, err := io.WriteString(conn, "GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"); err != nil { t.Fatal(err) }
    response, err := io.ReadAll(conn)
    if err != nil { t.Fatal(err) }
    if !bytes.Contains(response, []byte("finalmask-ok")) { t.Fatal("HTTPS response missing") }
}
