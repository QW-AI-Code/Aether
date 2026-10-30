package tls

import (
    "crypto/sha256"
    "testing"
    stdtls "crypto/tls"
    "net/http/httptest"
)

func TestAetherLeafPin(t *testing.T) {
    cert := []byte("a synthetic leaf certificate")
    sum := sha256.Sum256(cert)
    pin := append([]byte{0xA6}, sum[:]...)
    r := RandCarrier{PinnedPeerCertificateChainSha256: [][]byte{pin}}
    if err := r.verifyPeerCert([][]byte{cert}, nil); err != nil { t.Fatal(err) }
    if err := r.verifyPeerCert([][]byte{[]byte("different certificate")}, nil); err == nil {
        t.Fatal("wrong certificate bypassed pin")
    }
    if err := r.verifyPeerCert(nil, nil); err == nil { t.Fatal("empty chain accepted") }
    // A successful name verification branch may not override the pin.
    r.VerifyPeerCertInNames = []string{"example.com"}
    if err := r.verifyPeerCert([][]byte{[]byte("different certificate")}, nil); err == nil {
        t.Fatal("name override bypassed leaf pin")
    }
}

func TestAetherLeafPinHandshake(t *testing.T) {
    server := httptest.NewTLSServer(nil)
    defer server.Close()
    sum := sha256.Sum256(server.Certificate().Raw)
    config := &Config{AllowInsecure: true, PinnedPeerCertificateChainSha256: [][]byte{append([]byte{0xA6}, sum[:]...)}}
    conn, err := stdtls.Dial("tcp", server.Listener.Addr().String(), config.GetTLSConfig())
    if err != nil { t.Fatal("correct explicit pin did not allow self-signed certificate:", err) }
    conn.Close()
    config.PinnedPeerCertificateChainSha256[0][1] ^= 1
    conn, err = stdtls.Dial("tcp", server.Listener.Addr().String(), config.GetTLSConfig())
    if conn != nil { conn.Close() }
    if err == nil { t.Fatal("TLS handshake accepted a mismatching leaf pin") }
}
