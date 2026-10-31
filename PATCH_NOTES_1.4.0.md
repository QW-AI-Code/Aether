# Aether Mobile 1.4.0 — patch notes

App 1.4.0 (version code 16), engine core **2.0.0 → 2.1.0**.

## Engine (native/aether)
- Vendored core replaced with upstream 2.1.0 (new: `psiphon.rs`, `exitloc.rs`,
  `stats.rs`, `psiphon-build.sh`; updated Dockerfile, release workflow, docs).
- App patches rebased three-way against `.upstream-baseline` (2.0.0):
  `Cargo.toml`, `src/lib.rs`, `src/prober.rs`, `src/wg_prober.rs`. Unchanged
  upstream, kept verbatim: `netstack.rs`, `quic.rs`, `sysprofile.rs`,
  `upstream.rs`, `wireguard.rs`, `build.rs`. smoltcp stays pinned to 0.12 + CUBIC.
- `Cargo.lock`: the app's lock with the crate version bumped to 2.1.0.
- New app patch `tor-only-psiphon-chain` (lib.rs): `--tor-only --psiphon`.
- Build fix (lib.rs `spawn_udp_forwarder`): merge leftovers in the gool relay
  (E0425 `known`/`n`) resolved; upstream 2.1.0 peer-follow + app 1.2.8 bounded
  handoff now live in one clean receive loop.
- `.upstream-baseline` refreshed to 2.1.0; `CORE_VERSION` = 2.1.0;
  `scripts/sync-core.sh` floor raised to 2.1.0.

## Psiphon: old engine removed, new one wired
- Removed: `app/libs/psiphontunnel-2.0.39.aar`, `app/libs/PROVENANCE.md`,
  `transport/PsiphonHealth.kt`, the AAR `fileTree` dependency (all listed in
  `.github/removed-sources.txt`).
- Kept: `app/src/main/assets/server_entries.txt`, now handed to the engine's
  Psiphon as `-serverList` (engine patch `psiphon-embedded-servers`).
- `core/PsiphonBootstrap.kt` (new): system CA store exported as a PEM bundle for
  the Go client (`SSL_CERT_FILE`, `SSL_CERT_DIR`, `TrustedCACertificatesFilename`).
- `transport/PsiphonTransport.kt`: rewritten; waits for the engine's Psiphon and
  puts `PsiphonSocksFront` (kept) in front of it; automatic-exit fallback.
- `core/PsiphonEngine.kt` (new): Psiphon state read from the engine log.
- `model/Profile.kt`: transient `enginePsiphon`; `--psiphon`, `--psiphon-bind`,
  `--psiphon-region`; `AETHER_PSIPHON_READY_SECS` for `Tor → Psiphon`.
- `core/AetherProcess.kt`: `AETHER_PSIPHON_BIN` (`libpsiphon.so`),
  `AETHER_PSIPHON_DIR` (`psiphon-engine/`), orphan reaper.
- `vpn/AetherVpnService.kt`, `transport/ExternalTransport.kt`: rewired.
- `scripts/build-natives.sh psiphon` + CI step + APK check for `libpsiphon.so`.

## Smart routing (#66, #67)
- `core/SmartRoutes.kt` (new): app-side rule engine (same grammar/precedence as the
  engine's `routing.rs`), SNI/Host sniff, DNS sinkhole, address -> name memory, and the
  TCP/UDP verdicts the Psiphon front acts on. Test `SmartRoutesTest`.
- `core/SmartLists.kt` (new) + `assets/routing/` (new): built-in Iranian / ad lists,
  weekly refresh through the tunnel, routes file for the engine, rules for the front.
- `transport/PsiphonSocksFront.kt`: `setRoutes()`; block / direct / sniff for CONNECT,
  sinkhole / block / direct for UDP ASSOCIATE, DNS answers fed to the name memory.
  No rules = the old path, untouched.
- `vpn/AetherVpnService.kt`: `connectExternal` hands `SmartLists.frontRules()` to the
  front before it starts.
  `connectAetherStage`: in a Psiphon mode stage 1 gets no user block/direct rules.
- Engine: `routing.rs`, `socks.rs`, `cli.rs`, `lib.rs` (routes file, sinkhole, name
  memory, MIM inner QUIC with h2 fallback).
- `model/Profile.kt`, `data/ProfileStore.kt`, `core/AetherController.kt`,
  `core/AetherProcess.kt`, `ui/settings/SettingsScreen.kt`, `strings.xml` (en/fa): the two
  switches.

## Uplink SQM
- `transport/UplinkGovernor.kt` (new), wired into `transport/PsiphonSocksFront.kt`;
  test `UplinkGovernorTest`. Analysis: `docs/UPLINK_SQM_1.4.0.md`.

## VPN sharing
- `core/ShareBridge.kt` (rewritten), `core/ShareLeakGuard.kt` (new),
  `core/SharePages.kt` (new), `ui/SharePanel.kt`, `data/ShareCredentials.kt`;
  test `ShareLeakGuardTest`.

## Zero Trust sign-in
- New: `core/ZeroTrustWeb.kt`, `core/ZeroTrustSignIn.kt`, `core/TeamSignInRecord.kt`,
  `core/TeamMembership.kt`, `core/TeamSignInHandoff.kt`, `data/TeamSignInStore.kt`,
  `ui/settings/ZeroTrustMembership.kt`; tests `ZeroTrustWebTest`,
  `ZeroTrustSignInTest`, `TeamSignInRecordTest`, `TeamMembershipTest`, `ZeroTrustEnvTest`.

## AI
- New: `ai/AiMarkdown.kt`, `ui/ai/AiRichText.kt`. Updated: `ai/AiModelPolicy.kt`,
  `ai/AiPrompts.kt`, `ai/AiSession.kt`, AI screens.

## UI
- New: `ui/components/FitLineText.kt`. Updated: home screen (`FitToHeight`),
  settings rows (`BaseRow`, `SegmentedSelector`, `SettingsChoiceRow`).

## Build and release pipeline
- `scripts/enforce-release-tree.sh` (new) + workflow steps **Enforce release tree**
  and **Commit release-tree cleanup** (first steps after checkout): the repository is
  reduced to exactly the files in `SOURCE_MANIFEST.sha256` when a release is uploaded
  over an older one.
- Disk-space steps: **Free runner disk space**, **Reclaim disk space (native build
  intermediates)**, **Reclaim disk space before the Gradle cache save**.

## Security
- `docs/SECURITY_AUDIT_1.4.0.md`: 92 / 100.

## Unchanged
Exit countries and flags, backend picker.

## CI fix: release-tree step no longer blocks on a differing file

The first build after uploading 1.4.0 over 1.3.0 stopped in "Enforce release tree"
with `0 missing, 1 different. Nothing was deleted.` The differing file's name was
only emitted as an annotation, so it did not appear in the raw log.

`scripts/enforce-release-tree.sh` now:
- prints every differing file as `DIFFERENT: <path>` in the log and job summary;
- keeps going when files merely differ (the purge only depends on which paths belong
  to the release, not on their content); files CI rewrote in 1.3.0 (core
  auto-upgrade, READMEs), Dependabot bumps or hand edits no longer block the release;
- still refuses when any file is MISSING, or when more than `MAX_CHANGED` (25) files
  differ (manifest and tree from different releases).

Version unchanged: 1.4.0. `SOURCE_MANIFEST.sha256` regenerated for this package.
