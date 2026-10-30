package conf

import (
    "bytes"
    "encoding/hex"
    "testing"
    "github.com/xtls/xray-core/transport/internet/tls"
)

func TestAetherLeafPinConfig(t *testing.T) {
    text := "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
    message, err := (&TLSConfig{PinnedPeerCertSha256: text}).Build()
    if err != nil { t.Fatal(err) }
    config := message.(*tls.Config)
    hash, _ := hex.DecodeString(text)
    if !config.AllowInsecure || len(config.PinnedPeerCertificateChainSha256) != 1 ||
        !bytes.Equal(config.PinnedPeerCertificateChainSha256[0], append([]byte{0xA6}, hash...)) {
        t.Fatal("leaf pin was not mapped to the private verifier")
    }
    if _, err := (&TLSConfig{PinnedPeerCertSha256: "invalid"}).Build(); err == nil { t.Fatal("invalid pin accepted") }
}
