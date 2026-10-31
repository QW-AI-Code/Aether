# Aether 1.6.0

App version 1.6.0, base versionCode 19, PATCHLEVEL 1.6.0. Split codes:
19001 (ARMv7), 19002 (ARM64), 19003 (universal), 19004 (x86_64).
The Aether engine remains 2.3.0; the six ordinary connection backends are not replaced.

- Encrypted store v2 migrates existing configs with verification on and auto-select off.
- Named tabs: create, rename, move configs, delete tabs without deleting configs.
- One link/file import flow, encrypted hosted sources and atomic refresh; no separate source-creation menu or filter chips.
  Failed downloads/parses retain the previous data; unchanged outbounds retain identity/membership.
- Binary-safe file/hosted-config import. NPVT1 support retained; authenticated NPVS v5 compact-v1
  envelopes support app-key and passphrase unlock, P-256 signatures, ChaCha20-Poly1305 fields,
  HKDF, author expiry/mobile-only policies and sentinel mapping. Device-recipient/attestation flows,
  other NPVS generations and password work factors over 2 million are explicitly refused.
- Exported file-import activity validates local content/file URIs, prompts for passwords and
  destination group, and requires confirmation before saving. It never starts a VPN automatically.
  Generic MIME matching is needed for opaque Android attachment URIs and may show Aether for other files.
- Separate leaf-pinning Xray executable backported from pinned v25.12.8 sources; original Xray and
  ordinary engines remain unchanged. Missing optional binary fails closed, never silently ignores a pin.
- Custom readiness uses a small end-to-end HTTPS request, no upload/geo-IP/UDP readiness gate.
  Absolute 12-second request budget, two attempts. The default-on switch skips this custom gate
  when off, not core startup, ordinary chain bootstrap or ongoing liveness.
- Foreground-VPN auto-select: 1/3/5/10/30/60-minute intervals; eligible current-group candidates,
  otherwise same hosted config list/manual pool; fresh successful results only; 50 ms/20% hysteresis.
  Switching reconnects (brief interruption); rankings measure the config directly, not its entire chain.
- Real delay uses the v2rayNG default HTTPS 204 target, includes outbound CONNECT/TLS/response time,
  validates destination certificates and refuses error/redirect/captive-portal statuses. Two rounds,
  fastest successful result. This is methodological alignment, not device-side numeric calibration.
- Complete Gradle Wrapper plus distribution checksum; CI runs debug/release unit tests before packaging.

See `FUNCTIONAL_BUILD_VALIDATION.md` for current executed commands and validation limits.
Earlier build/security-only reports are historical. Local compiler/package checks do not
certify a real-device VPN session or a GitHub-hosted workflow run.
Private sample credentials and local validation keys are never included in the release ZIP.

Detailed Persian notes: `RELEASE_1.6.0.fa.md`.

## Same-version security/CI correction

Quinn/TLS dependency fixes, strict auditable CI outcomes and preserved maintenance
warnings. See `docs/SECURITY_CI_FIX_1.6.0.md` for fresh validation and limits.

## Same-version functional repair

New catalog/table, repaired exit-IP path, immediate selection feedback and built-in
verification setting. See `FUNCTIONAL_FIX_1.6.0.md` and the root
`FUNCTIONAL_BUILD_VALIDATION.md` for current checks and limits.
