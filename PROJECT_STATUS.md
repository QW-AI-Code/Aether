# Final package status

- Version: 1.4.0
- Base versionCode: 16
- App patch level (`PATCHLEVEL`, stamped into `libaether.so`): 1.4.0
- Engine core: 2.1.0 (Psiphon runs inside the engine, `libpsiphon.so`)
- Backends: Aether, Aether -> Psiphon (chained), Tor, Aether -> Tor,
  Tor -> Psiphon, Tor -> Aether
- Protocols: WireGuard, MASQUE, GOOL, WARP-in-WARP, MASQUE-in-MASQUE (`--mim`)
- Exit selection: Psiphon region filter with automatic fallback; Tor bridge
  country; flag emoji on every country row
- Loopback ports: engine SOCKS5 1819, Tor listener 1820, Tor front 1821, chained
  second stage (Psiphon front) 1825, Psiphon's own listener 1827 — now **preferred**
  values, resolved per session by `core/PortLease.kt` (new in 1.3.1). The preferred
  port is used whenever free, so nothing moves on a single-profile install.
  LAN sharing (10810/10811, `core/ShareBridge.kt`) is still fixed — see below.
- Architectures: arm64-v8a, armeabi-v7a, **x86_64** (new in 1.3.1)
- Form factors: phone + **Android TV** (new in 1.3.1 — a compatibility declaration,
  not a leanback UI; D-pad traversal is not designed for)
- Archive integrity: `SOURCE_MANIFEST.sha256` — regenerated for this package, see
  the caveat at the bottom
- Security audit (1.4.0): `docs/SECURITY_AUDIT_1.4.0.md` — 92/100
- Security audit (1.3.0): `docs/SECURITY_AUDIT_1.3.0.md` — 88/100 audited,
  93/100 after the seven remediations shipped in the same build
- Uplink SQM analysis (1.4.0): `docs/UPLINK_SQM_1.4.0.md`
- Release tree: `scripts/enforce-release-tree.sh` removes every file that is not in
  `SOURCE_MANIFEST.sha256` on the first build after an upload
- Media-stall root cause analysis (1.2.8): `docs/MEDIA_STALL_1.2.8.md`
- Upload-stall root cause analysis (1.2.8-r8): `docs/LIVE_STREAM_STALL_1.2.8-r8.md`

## What 1.4.0 changed

Engine core 2.1.0 with Psiphon inside the engine, uplink SQM for Psiphon chains,
rewritten VPN sharing, Zero Trust sign-in before connect, AI answer rendering and
accuracy, home-screen layout fixes, and the release-tree cleanup in CI. Full detail
in `CHANGELOG.md`, file by file in `PATCH_NOTES_1.4.0.md`.

1.4.0 also ships every 1.3.1 fix below (1.3.1 was not published on its own).

## What 1.3.1 changed

Maintenance only; no transport or protocol work. Full detail in `CHANGELOG.md`.

1. **Cold start.** `DiagnosticsLog.init` no longer decrypts the whole encrypted
   diagnostics log on the main thread. Restore moved to a background thread and
   only the retained tail is decrypted (`EncryptedLogFile.readLastLines`); the log
   writer coalesces a trickle of lines into one sealed record.
   Fixes the ~10 s cold start, the black-screen-until-clear-data reports, and the
   cause behind the `ForegroundServiceDidNotStartInTimeException` crashes.
2. **Widget + notification.** Repaint on the final disconnect step and in
   `onDestroy`; a persisted state (`widget/WidgetStateCache`) so a cold receiver
   process paints truthfully, with transient states mapped to Idle; the
   notification action relabelled from a state to `notif_action_disconnect`; the
   orphaned notification cancelled explicitly. Widget is now 1x1 and resizable,
   and the icon is tinted by state.
3. **Dual SIM.** `SmartAuto.readOperator` reads the subscription that is actually
   carrying data instead of the default (usually SIM 1).
4. **x86_64.** Added to `abiFilters`, the ABI split, `abiCodes` (offset 4,
   appended), `ABIS` in `scripts/build-natives.sh`, the CI Rust target list and
   the release artifact map.
5. **Private Space (#27, #52).** New `core/PortLease.kt`: the five loopback ports
   are resolved per session, preferred first. `Profile.toArgs()` now always passes
   `--bind` (and `--tor-bind`), which core 2.0.0 already accepted and the app never
   used — that default is where the fixed 1819 came from. The three `const val` in
   `AetherVpnService`'s companion became accessors over it.
6. **Build.** The Tor feature check no longer trips `pipefail` via `grep -q`.
7. **Lint.** `windowLayoutInDisplayCutoutMode` moved to `res/values-v27/themes.xml`;
   the project's only lint error, present in 1.3.0 as shipped.
8. **Endpoint cache.** `fastEndpointOnly` now defaults OFF and its budgets are
   recalibrated; it was discarding every cached endpoint on real networks. The
   MASQUE-side engine half ships as `patches/0001-…` and is NOT applied.
9. **Zero Trust (#12).** The e-mail one-time code is asked for during a connect:
   the engine's non-TTY marker line is parsed and the code written back to its
   stdin (`core/LoginCodePrompt.kt`, `ui/LoginCodeDialog.kt`). Enrolment tokens are
   checked as they are pasted (`core/AccessToken.kt`, 14 tests). Full picture,
   including what still needs a JNI binding: `docs/ZERO_TRUST.md`.
10. **Android TV (#21).** Manifest declarations + banner; the APK is installable and
    listed on a TV.
11. **Free MTU entry (#33)**, bounded 1280-9000.
12. **Gemini (#31, #11).** The key-rejection message names Google's 2026 retirement
    of standard API keys; `gemini-3.7-flash` added. The transport was already
    correct and was not touched.
13. **Custom ECHConfigList (#44)** — the base64 half the engine supports. Naming a
    domain, as the issue asked, needs engine work.
14. **Battery-optimisation prompt (#5)**, with no new permission.
15. **Per-app tunnelling (#3)** was already implemented; verified, not changed.

After the first field test of 1.3.1 on a handset, four more, version unchanged:

16. **The connection row named the second hop twice.** It read
    `Aether(WIREGUARD → PSIPHON) → Psiphon`. Both chained paths in
    `AetherVpnService` were publishing the whole chain through
    `EngineMeta.setProtocol` — correct before 1.3.1, when that value was printed
    raw; wrong once `pipelineLabel(transport)` started putting it inside the
    brackets. Both now publish the transport alone, and `pipelineLabel` keeps only
    the part before an arrow so it cannot recur.
17. **The endpoint row was empty.** `EngineMeta` only knew the two `selected …`
    lines, which the engine prints from the SCAN alone — and item 8 above made the
    cached-reuse path (which prints no such line) the ordinary one on reconnect. It
    now also reads `using cloudflare edge …`, logged on every path in
    `run_masque` / `run_wireguard` / `run_warp_in_warp`, and
    `masque-in-masque ready: …` for the one mode without it.
18. **Persian text under a technical field was left-aligned**, with the sentence's
    full stop at the wrong end. `LtrOutlinedTextField` pinned the LAYOUT direction
    to LTR for the whole field, which took `label`, `placeholder` and
    `supportingText` — Persian prose — with it. Only the value's text direction is
    pinned now. `scripts/fix-fa-bidi.py` re-run over `values-fa/strings.xml`: 0
    strings changed, so the resources were already right and this was a rendering
    defect. `AetherTheme` is now the only place in the app that provides
    `LocalLayoutDirection`.
19. **Choice sheets give every option its own card** (`SettingsChoiceRow`, used by
    every picker in the app), with the selected one tinted and outlined rather than
    only ticked.

Those four are covered by `TransportBackendLabelTest` and `EngineMetaTest` (new,
12 tests, taking the suite from 88 to 100) where they are testable without a
device.

20. **Smart races two routes and remembers per network** — inside the existing
    Smart entry, not as a second button. `Protocol.AUTO` asks
    `SmartPlusPlan.eligible(profile)`: the plain Aether backend with automatic
    endpoint selection gets the race, everything else gets the ladder it always
    had, and the settings screen says which. (This shipped for one round as a
    separate "Smart Plus" selector entry and was withdrawn: on a chained backend
    it silently behaved like Smart, and worse, `connectAetherStage` planned
    `AUTO_PLUS` as a hand-picked protocol so the engine got no protocol flag and
    could not connect at all.) If the race finds nothing it hands over to the
    ladder.
    It adds per-network memory (`core/SmartPlusMemory.kt`, keyed by
    `core/NetworkIdentity.kt`, no new permission) and a two-lane race
    (`core/SmartPlusPlan.kt`, `runRace`/`runLane` in the service). Two lanes rather
    than five because the engine's identity files decide what can run
    concurrently: `--wg`/`--gool` share `aether.toml`, `--masque`/`--mim` share
    `aether-masque.toml`. A race only SELECTS a strategy — lanes are throwaway
    engines on their own ports, verified with `Diagnostics.runProxyStage`, all
    killed afterwards — and the session is then established by the ordinary
    `connectAttempt`, so the TUN, the bridge, the self-test and the supervisor are
    untouched. Worst case 187 s instead of 330 s. 28 new unit tests.
    `IdentityVault.running` became a reference count for it, because sealing
    shreds the plaintext and a two-engine race would otherwise pull the WARP
    identity out from under a live lane.

21. **QUIC no longer comes and goes with every Psiphon rotation.**
    `PsiphonSocksFront` now keeps QUIC off for the rest of a
    session once any server has refused the udpgw port forward, instead of offering
    it again after every rotation — the state `docs/PSIPHON_MEDIA_STALL.md` §4
    warned about. The degradation itself is Psiphon's server pool, not this app:
    in the field log the Aether stage never reconnected and reported zero
    tail-drops for six minutes while the Psiphon tunnel died twice. A
    `ConnectionState.Repairing` that shipped in the same round was WITHDRAWN — it
    blanked the exit-IP pill on every repair, because `MainActivity` clears that
    pill for any busy state and refills it through the engine's port rather than
    the chained pipeline's front. Reasoning kept in `ConnectionState.kt`.

22. **Live audio no longer kills a chained session.** When a Psiphon server has
    refused the udpgw port forward, a non-DNS UDP flow is now ENDED (the
    association is closed) instead of having its datagrams dropped in silence, so
    Gemini Live and voice chat fall back to their own TCP path in about a second
    rather than retrying until the tunnel collapses. An association that has
    carried DNS is never closed, which keeps the change safe whether or not hev
    opens one association per flow. Real UDP does not come back: a server that
    refuses udpgw cannot carry it.

`README.md` and `README.fa.md` carry a plain-language section on what Smart does now
and how it differs from a hand-picked protocol.

## Known open, deliberately not addressed in 1.3.1

- **LAN sharing in a second Android profile.** `ShareBridge` binds 10810/10811 and
  RETRIES the same port rather than moving, so the Private Space fix does not extend
  to it: a second instance still cannot share over the LAN. Deliberate — those are
  addresses a user types into a PC or a TV. Issue #28 asks for them to be
  configurable, which is the change that belongs here.
- **The Smart race is UNVERIFIED on a device.** The planner's rules are unit-tested and
  the lane mechanics compile and pass lint, but a race only means anything on a
  real network. Nothing has been observed: two engines side by side, the loser
  killed cleanly, the winner's gateway still cached for the confirming connect, or
  the per-network memory making a second connect quick.
- **Private Space is fixed but UNVERIFIED on a device.** It needs two Android
  profiles and an engine binary to check, and neither was available where this was
  built.
- **MASQUE / MASQUE×2 / WireGuard not connecting (#47, #46, #45, #43, #39, #38,
  #42).** Not an app defect. Every field log ends at
  `api.cloudflareclient.com/v0a4471/reg` being unreachable, with the camouflaged
  route timing out after it: WARP/MASQUE account registration is blocked on those
  carriers and the engine never reaches a handshake. `consts.rs` hardcodes the API
  host, version and the CDN anycast pool.
- **Zero Trust is authentication DURING a connect, not before it.** A true
  pre-login needs `ffi.rs`'s `aether_team_*` entry points, which need the engine
  loaded as a library rather than exec'd as a binary. See `docs/ZERO_TRUST.md`.
- **LAN sharing port / optional credential (#28, #35, #36): NOT done, by decision.**
  Removing the credential from a LAN-exposed proxy is audit finding F-5 of 1.2.9 and
  `LanGuard`'s tests exist because of it; it could not be exercised on a device here.
  Design in the CHANGELOG.
- Chinese (#25) dropped at the maintainer's request.

## SOURCE_MANIFEST.sha256 — regenerated, with one caveat

It HAS been regenerated for 1.4.0 and describes exactly the tree in this package.
It is also the list `scripts/enforce-release-tree.sh` enforces on the first build
after an upload: any tracked file that is not in it is removed.

The four quiche test certificates that older manifests carried
(`native/aether/quiche/{apps/src/bin,fuzz,quiche/examples,tokio-quiche/examples}/cert.key`)
are not part of 1.4.0 and not in its manifest. `native/aether/.gitignore` ignores
`*.key`, so they are never committed; if one was ever force-added, the release-tree
step removes it. They are read only by quiche's own examples and `#[cfg(test)]`
code at run time, never by the engine build.

To regenerate it yourself in a full checkout:

```bash
find . -type f -not -path './.git/*' -not -name SOURCE_MANIFEST.sha256 \
  | LC_ALL=C sort | xargs sha256sum > SOURCE_MANIFEST.sha256
```
