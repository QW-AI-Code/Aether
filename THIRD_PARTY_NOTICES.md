# Third-party notices

AetherMobile is distributed under AGPL-3.0-or-later. This release includes or builds against:

- Psiphon Tunnel Core Android 2.0.39. Its AAR and native components retain their upstream licenses and notices.
- hev-socks5-tunnel, Apache-2.0 / MIT.

Do not remove upstream copyright or license files when redistributing binaries or modified source. AGPL source-offer obligations still apply to distributed builds and network deployments.

## Psiphon (1.4.0)
psiphon-tunnel-core (GPL-3.0), built from source by the engine's
`psiphon-build.sh` and shipped as `libpsiphon.so`. The previously bundled
`psiphontunnel` AAR is no longer included.

## Custom configs (1.5.0)
- Xray-core v25.12.8 (MPL-2.0), https://github.com/XTLS/Xray-core. The official
  release binaries are shipped unmodified as `libxray.so`; their source is
  available from the upstream repository at tag `v25.12.8`.
- ZXing core 3.5.3 and zxing-android-embedded 4.3.0 (Apache-2.0), QR code
  scanning and decoding.
- kotlinx.serialization-json 1.7.3 (Apache-2.0).
- The NapsternetV `.npvt` decoder (white-box AES-CTR tables in
  `app/src/main/assets/npvt/wb.bin`) is an independent Kotlin implementation
  for interoperability with files users already own.

## Tor directory and channel crates (1.5.0)
- `tor-chanmgr` 0.46.0 and `tor-dirclient` 0.46.0 from Arti (MIT OR Apache-2.0),
  https://gitlab.torproject.org/tpo/core/arti, vendored in
  `native/aether/vendor/` with small changes marked `AETHER-VENDOR-PATCH`
  (relay handshake without SNI behind an upstream proxy; directory timeouts
  for slow chains). Arti's LICENSE-MIT and LICENSE-APACHE are included in each
  vendored crate.

## NPVS v5 interoperability — Pantegnos (AGPL-3.0)

The compact-envelope/field mapping and generation-2 white-box tables used by
`custom/NpvsCodec.kt` and `assets/npvs/gen2-wb.bin` are adapted from
FrontierTM/Pantegnos, commit `5a599878c4c113d4d91d1da7294d43095bdb8433`:
https://github.com/FrontierTM/Pantegnos/tree/5a599878c4c113d4d91d1da7294d43095bdb8433
The tables are rearranged from interleaved round blocks into Aether's AWB1 layout;
the cipher and known-answer vectors are unchanged. Aether is also AGPL-3.0-or-later.
Full upstream license: `third_party/licenses/Pantegnos-AGPL-3.0.txt`.

Portable ChaCha20-Poly1305, PBKDF2 and P-256 verification use Bouncy Castle
`bcprov-jdk18on:1.80` (Bouncy Castle license, MIT-style):
https://www.bouncycastle.org/licence.html


## QWVPN-1.0.0 reference ports

The connection audio assets and short-lived MediaPlayer cleanup pattern are adapted
from QWVPN by QW-AI-Code, GPL-3.0. The license is preserved in
third_party/QWVPN/LICENSE. Config capsules and private-core throughput measurements
follow its UI/measurement methodology while using Aether's existing executable Xray
and store rather than its libv2ray ABI.

### QWVPN clean-IP scanner follow-up
`scanner/CleanIpScanner.kt`, `ScanProfile.kt`, and the selection/stability policy in
`CleanIpValidation.kt` are ported/adapted from QWVPN-1.0.0 under GPL-3.0.
The existing QWVPN license is at `third_party/QWVPN/LICENSE`. Package/preferences,
cancellation/socket cleanup, boundary validation, no verbose probe logging,
Aether's Xray measurement adapter, protected store updates, and Compose integration
are Aether-specific adaptations. Scanner discovery TLS is isolated from authenticated
proxy traffic; no scanner-evasion padding is included.
