# 1.2.7 implementation map

- `model/TransportBackend.kt`: backend enum - `AETHER` and the chained
  `AETHER_PSIPHON` - plus the `usesAetherEngine`, `usesExternal`, `isChained` and
  `pipelineLabel` helpers. `ExternalKind` splits "which external stack" from "is
  it chained". `AETHER_TOR` / `ExternalKind.TOR` were removed; `fromStoredName`
  maps the retired Tor names onto plain `AETHER` and `PSIPHON` onto
  `AETHER_PSIPHON`.
- `model/Profile.kt`: persisted backend and ISO exit region.
- `data/ProfileStore.kt`: DataStore migration-safe persistence (stores enum names).
- `core/AetherController.kt`: backward-compatible Intent codec fields.
- `core/TunnelConfig.kt`: `CHAIN_SOCKS_PORT` (1825) and `PSIPHON_SOCKS_PORT`
  (1827). Tor's `TOR_SOCKS_PORT` / `TOR_DNS_PORT` (1822 / 1823) are gone.
- `core/Diagnostics.kt`: `graceMs` parameter, `EXTERNAL_GRACE_MS` (150 s) for
  chained Psiphon sessions, and `runProxyStage()` - the chained stage-1 gate
  (port + handshake + real outbound TCP, no geo lookup, no self-test circles).
- `core/ShareBridge.kt`: relays into a configurable `upstreamPort` instead of a
  hardcoded 1819, so proxy mode and LAN sharing follow the chain's exit.
- `transport/PsiphonTransport.kt`: Psiphon lifecycle, `UpstreamProxyUrl` for the
  chain, configurable local SOCKS port, real bound port reported to the caller,
  automatic-exit + datastore-reset retry, own datastore directory.
- `transport/TorTransport.kt`, `transport/TorSocksFront.kt`: **deleted**
  with the rest of the Tor runtime (see docs/TRANSPORT_BACKENDS.md). Both paths are
  listed in `.github/removed-sources.txt` so a build over an older checkout purges
  the orphans.
- `transport/ExternalTransport.kt`: factory that derives the upstream URL and the
  listen port from the profile's backend.
- `transport/ExitRegions.kt`: expanded country list with flag-emoji labels
  (`NetProbe.flagEmoji`), plus a flag-free `name()` for logs.
- `vpn/AetherVpnService.kt`: common TUN/health/teardown orchestration for all
  backends; `connectExternal` now drives the two-stage chain, `connectAetherStage`
  runs stage 1 through the existing ladders with `stageOnly = true`, and
  `startTun2Socks` / `writeHevConfig` take the SOCKS port as a parameter.
- `ui/AdvancedPanel.kt`: backend and exit-country selectors, per-backend helper
  text, capability-based enablement.
- `scripts/build-tor.sh` and its release-workflow step: **deleted**.


## 1.3.0 additions (engine core 2.0.0)

- `transport/TorSocksWire.kt`: the byte-level SOCKS5 UDP and DNS-over-TCP framing,
  pure and total, with `app/src/test/.../TorSocksWireTest.kt` behind it. Split out of
  the front so the one part of the Tor path that fails invisibly can be tested at
  all; `TorSocksFront` keeps the sockets, threads and counters.
- `transport/TorCountries.kt`: the short bridge-country list for bridgedb, separate
  from `ExitRegions` because it answers the opposite question.
- `model/Profile.kt`: `torCountry`, `torDirectSecs` and `torCheck` with strict
  sanitizers, emitted into `toEnv()` only when Tor runs and only when they differ
  from the engine's defaults.
- `model/TransportBackend.kt`: six modes and `TorMode` (CHAIN/ONLY/REVERSE); every port and pipeline
  decision is a computed property of the backend, so no caller can pair a mode with
  the wrong port.
- `transport/TorSocksFront.kt` (new): the UDP-capable SOCKS front Tor needs on
  Android. See the file header for why a Tor listener cannot be handed to
  hev-socks5-tunnel directly.
- `core/TunnelConfig.kt`: `TOR_SOCKS_PORT` 1820, `TOR_FRONT_PORT` 1821.
- `model/Profile.kt`: `Protocol.MIM`, `TorBridges`, `torBridgeLines` +
  `sanitizedBridges()`; `toArgs()` emits the Tor flags first and returns early in
  `--tor-only`, where the WARP flags describe work nothing would read.
- `core/AetherProcess.kt`: Tor directory cache and pluggable-transport paths.
- `vpn/AetherVpnService.kt`: `connectTor()`, the Tor bootstrap budget, the stage
  backend fix, and the front's teardown ordering.
- `transport/ExternalTransport.kt`: Psiphon's upstream port follows the backend.
- `ai/AiPatch.kt`, `ai/AiTopic.kt`, `ai/AiGate.kt`: Tor as read-only AI context.
- `scripts/build-natives.sh`, `.github/workflows/build.yml`: `--features tor`,
  binary verification, and the lyrebird target.


## UI map (home screen and connection card)

- `ui/components/GlowCycle.kt` (new): the shared travelling-light engine. One
  `animateFloat` from 0 to the number of palette colours drives both the lap phase
  and the lap colour, and `drawGlowCycle()` draws the equaliser bands along any
  path. Used by the card edge and the connect button, so they cannot drift apart.
- `ui/components/ConnectionCard.kt`: the three-column protocol strip became three
  full-width slides (`MetaSlides` / `MetaSlide`) inside the same card, the new
  `PingStrength` waveform lives on the latency slide, the card holds the last good
  ping reading, and `glassEdge` now delegates its band drawing to `GlowCycle`
  (the duplicate local `TWO_PI` and band table were removed with it).
- `ui/components/ConnectButton.kt`: one 96 dp bolt with an additive core when
  connected, plus `cycleRing` - the card's light show drawn around the disc while
  connected.
- `ui/HomeScreen.kt`: screen padding 32 -> 18 dp, the gap under the title
  28 -> 10 dp and the gap under the button 28 -> 6 dp, which pays for the card's
  extra slides without pushing the block off the fold.
- `res/values*/strings.xml`: `meta_ping_strength` and `ping_quality_*` in English
  and Persian.

## 1.4.0
- Engine rebased onto core 2.1.0. New patch in `native/aether/aether/src/lib.rs`:
  `tor-only-psiphon-chain` (lets `--tor-only` carry the engine's Psiphon).
- The Psiphon AAR was removed; Psiphon is the engine's (psiphon-tunnel-core
  console client, built from source by `native/aether/psiphon-build.sh`).


## 1.5.0 additions (engine core 2.3.0)

- Engine rebased onto core 2.3.0; details and hand resolutions (lib.rs, psiphon.rs, socks.rs, Cargo.lock) in `native/aether/.upstream-baseline/README.txt`.
- `app/build.gradle.kts`: versionName 1.5.0, versionCode 18; `PATCHLEVEL` 1.5.0.
- Tor over an upstream proxy: `native/aether/vendor/tor-chanmgr` (relay TLS without SNI when `AETHER_TOR_LINK_SNI=off`, set by `Profile.toEnv` whenever Tor has an upstream) and `native/aether/vendor/tor-dirclient` (20 s BEGIN_DIR / 20 s read-idle / 180 s total directory timeouts), both arti 0.46.0, wired in by the `tor-slow-chain` block in `native/aether/aether/Cargo.toml`. If a core upgrade moves arti off 0.46, cargo reports the patch as unused and the vendored copies must be refreshed.
- `native/aether/aether/src/tor.rs` (`tor-cache-seed`): bootstrap attempts share a snapshot of the freshest directory cache.
- `custom/XrayConfigBuilder.kt`: chain-level `uplinkOnly` 10 s / `downlinkOnly` 30 s.
- Clipboard import: `ui/components/LtrInput.kt` (`replaceKey`), `ui/settings/CustomConfigsPage.kt`, `custom/ConfigParser.kt` (`cleanInput`, `extractLinks`), `custom/ConfigImporter.kt`.
- `vpn/AetherVpnService.kt`: Psiphon-only start rethrows `CancellationException`.
- Real delay test: new `custom/RealDelay.kt` (one Xray process per batch, password-protected loopback SOCKS inbound per config, HTTP `generate_204` through it); `custom/ConfigPing.kt` (TLS probe and trust-all `TrustManager` removed, `diagnose`, `NO_REPLY` / `INVALID`); `custom/ConfigPingStore.kt` (serialised runs, logged results); `custom/XrayConfigBuilder.kt` (`directOutbound`, `directDns`); `ui/settings/CustomConfigsPage.kt` and `res/values*/strings.xml` (`cc_ping_noreply`, `cc_ping_invalid`).
- Security fixes from `docs/SECURITY_AUDIT_1.5.0.md`: `custom/XrayProcess.kt` (`redact` masks UUIDs, host names, IPv6, IPv4 /16), `custom/ConfigImporter.kt` + `vpn/AetherVpnService.kt` (`activeForwardPort`: subscription downloads through the session), *Copy link* via `copySensitive`.
- Tests: `app/src/test/.../custom/RealDelayTest.kt` (incl. `XrayLogRedactionTest`).

## 1.6.0

See `docs/RELEASE_1.6.0.md` and `BUILD_VALIDATION.md`. Native Aether engine sources were not modified. The optional leaf-pin Xray backport is source-pinned, independently built, and selected only for configs requiring it.


## Additional 1.6.0 UI and feedback changes

- Preserve phase-one immutable Default migration and storage/UI guards.
- Use LTR-safe capsule cards, compact tabs and top ping/import actions.
- Add server-specific real throughput tests and optional connection-state audio.
- Keep versionName 1.6.0 and base versionCode 19.

## Independent clean-IP scanner

- Share the same scan depth and manual real-ping ceiling between single and group scans.
- Keep endpoint identity and ownership; reject stale or locked writes.
- Run cancellable work off the UI thread and cancel before a VPN session starts.
- Display progress on the active server row and a result card with old/new endpoints.
- Preserve the original QWVPN license and scanner/validation attribution.

## UI repair follow-up — 1.6.0 (versionCode 19 unchanged)

Removed tab close controls, compacted row numbering, sanitized technical labels,
persisted and enforced the selected custom-config tab across connection/ranking,
expired scan notices/results, repaired asynchronous country enrichment, and put
upstream engine credits before the UI-only QW-AI-Code description in both locales.
Added model, storage, monitoring, lifecycle and native Compose-render regressions.
See `docs/UI_REPAIR_1.6.0.fa.md` for behavior, validation and limitations.

## 1.6.0 / 19 — CI audit and scanner AI follow-up
- Fixed the log-confirmed RustSec gate failure: Cargo-resolved rustls-webpki 0.103.16 and webpki-roots 1.0.10; security floors also cover sync/fallback engine builds. Strict audit remains enabled.
- Added scanner profile/limit Gemini help, safe live read-only context in explanations/cache/chat, Persian offline guidance, and missing semantic settings/drawer topics.
- Added security and AI coverage/Compose regressions; CI collects repair renders outside the checkout. Full scope and validation limitations: docs/CI_AI_REPAIR_1.6.0.fa.md.
- Fixed a locally reproduced first-subscription notice race: process initial store/connection snapshots instead of dropping them; regression uses no initialization sleep or increased timeout.
