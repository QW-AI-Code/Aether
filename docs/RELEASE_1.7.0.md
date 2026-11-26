# Aether 1.7.0 - connection reliability and the Smart-mode rewrite

App 1.7.0 (version code 20), engine core 2.4.0 (unchanged). Persian: [RELEASE_1.7.0.fa.md](RELEASE_1.7.0.fa.md).

## 1. Field evidence (Tehran, IR-MCI, Wi-Fi, 1.6.0)

**Manual MASQUE log**

```
00:00:16.165 Spawned libaether.so --bind 127.0.0.1:1819 --masque --balanced -4 --quick-reconnect
00:00:16.183 [*] scan mode=balanced ... candidates=2079 ... budget=120s
00:00:16.474 engine-socks-open after=369ms        <- port bound, engine still scanning
00:00:20.528 socks5 handshake = false             <- exactly 4.0 s later
00:00:20.862 Spawned ... --masque ... --noize firewall --fragment --ech auto
00:00:20.883 Error: Ech("ECH is on but there is no ECH key to offer ...")
00:00:21.171 engine-fail category=died after=428ms budget=150000ms
```

**Smart-mode log**

```
race[wg,wg.noize,gool@+0s | masque.h3,masque.h2,mim@+7s]
lane 1 wg        : stage 1 ... does not speak SOCKS5      (+4.0 s, engine "verifying cached endpoint")
lane 2 masque.h2 : cached gateway 162.159.198.2:443 still works -> tunnel validated -> carries traffic
confirm masque.h2: connect-ip 200, "validating data-plane" ... socks5 handshake = false (+4.0 s)
ladder for OPEN  : WIREGUARD, MASQUE(h3, ranges 8.6.112.0/24 ...), GOOL, WIREGUARD - each killed at +4.0 s
```

## 2. Root causes

| # | Defect | Where | Effect |
|---|--------|-------|--------|
| 1 | An open port was taken for a ready engine; the self-test then gave a single 4 s SOCKS5 handshake | `AetherVpnService.connectAttempt`, `Diagnostics.run`, Smart lanes, supervisor restart | Every attempt whose engine needed > 4 s (cached-gateway verify alone is 2-3.5 s on Iranian links; any scan is longer) was killed. Main cause of "connection failed" in all modes. |
| 2 | Anti-DPI passes forced `--ech auto` | `directPlan`, `SmartAuto.buildPlan`, `SmartPlusPlan` | No ECH key in Iran -> engine refuses to start (fail-closed by design) -> hardened MASQUE always died in 0.4 s. |
| 3 | Unfair race: WireGuard lane at +0 s, MASQUE at +7 s, preceded by a serial 45 s remembered-route slot | `SmartPlusPlan` | Where only MASQUE passes, Smart spent its time on WireGuard. |
| 4 | Gool (core 2.4.0: WireGuard inside MASQUE, MASQUE identity + cache) raced in the WARP lane | `SmartPlusPlan.Family` | Two engines on `aether-masque.toml` and the MASQUE gateway cache at once. |
| 5 | First route to answer won, regardless of latency, speed or overhead | `runRace` | Not the best route; no comparison at all. |
| 6 | Ladder narrowed MASQUE/Gool scans to WireGuard ranges and had no MASQUE-over-HTTP/2 rung on "open" networks | `SmartAuto.buildPlan` | MASQUE scanned `8.6.112.0/24` (not a MASQUE range); the carrier that works in Tehran was never tried. |

## 3. Fixes

### 3.1 Readiness gate (`core/EngineReadiness.kt`, `core/AetherProcess.kt`)

`AetherProcess` records `servingAtMs` when the engine logs `socks5 server listening on` (printed by
`socks::serve` only once a tunnel is validated) and `fatalLine` for `Error: ...`.
`EngineReadiness.await(engine, host, port, budget)` returns `READY` once the engine serves **and** a real
SOCKS5 greeting succeeds (a blind greeting every 2 s covers builds whose serving line differs),
`DIED` the moment the process exits, `TIMEOUT` at the end of the budget. It is used:

- after the port opens in `connectAttempt` (remaining attempt budget, at least 8 s), except for
  Tor-fronted stages and custom-upstream engines which keep their bootstrap-aware waits;
- in each Smart lane before the outbound-TCP proof;
- in `superviseEngine` before the self-test of a restarted engine.

### 3.2 Smart mode (`core/SmartPlusPlan.kt`, `AetherVpnService.connectSmartPlus/runRace/runLane`)

- Two lanes (WARP / MASQUE identity families), both at +0 s; budgets by weight only:
  light 20 s, hardened 25 s, two-hop 40 s; race ceiling 150 s.
- WARP lane: `wg` -> `wg.noize` -> `wg.heavy` (hardened first on UDP-throttled / hostile networks);
  classic Gool last when selected.
- MASQUE lane: `masque.h3` / `masque.h2` (h2 first on mobile data, Iranian SIM, UDP throttling or an
  unanswered QUIC probe; `h2`, `h2.frag`, `h3` under SNI filtering) -> `masque.h2.frag` -> `gool` -> `mim`.
- After the first proof the race runs `QUALITY_WINDOW_MS` = 3.5 s more; lanes still searching are then
  stopped, proven lane engines are held and measured one after another (`NetProbe.measureTunnelQuality`:
  best of three `1.1.1.1:80` round trips, ~384 KB download and 128 KB upload over verified HTTPS to
  `speed.cloudflare.com`).
- `SmartPlusPlan.score = (4·rtt + ½·t(1 MB down) + ½·t(256 KB up)) × overhead`; unmeasured transfers
  are charged as slow ones. Overheads: wg 1.00, wg.noize 1.03, wg.heavy 1.06, masque.h3 1.05,
  masque.h2 1.10, masque.h2.frag 1.15, gool 1.25, mim 1.35.
- Confirmation goes through `connectAttempt` (60 s cap) in ranked order; then a deep last resort
  (`masque.h2`, `wg`, full built-in ranges, Balanced or the user's deeper scan mode).
- Memory: the proven route leads its lane; failures of every lane are recorded per network.

### 3.3 Fingerprint (`core/SmartAuto.kt`)

- QUIC probe: one 1200-byte long-header packet with reserved version `0x1a2a3a4a` to
  `162.159.198.1/.2:443`; a Version Negotiation reply proves UDP/443 to the MASQUE gateways.
- `iranSim`: MCC 432 on the data SIM, also on Wi-Fi.
- The serial ladder (chains, pinned endpoints) is built from the same catalogue
  (`SmartPlusPlan.ladder`), families interleaved; WireGuard-only range narrowing.

### 3.4 Manual protocols (`directPlan`)

As configured -> (MASQUE over HTTP/3 only) MASQUE over HTTP/2 -> anti-DPI pass (HTTP/2 + TLS
fragmentation + obfuscation). ECH is never forced.

## 4. CI dependency audit (build of 2026-11-23)

The first 1.7.0 CI run built everything and then failed in the final strict `cargo audit` on six
advisories published after 1.6.0. Fixes, in `scripts/patch-engine-dependencies.py` (applied before
the engine compiles, `cargo update --precise`):

| Package | Locked | Floor | Advisories |
|---------|--------|-------|------------|
| quinn-proto | 0.11.17/0.11.18 | 0.11.19 | RUSTSEC-2026-0523, RUSTSEC-2026-0524 |
| quinn-udp | 0.5.15 | 0.5.16 | RUSTSEC-2026-0538 |
| url | 2.5.8 | 2.5.9 | RUSTSEC-2026-0592 |
| cookie | 0.18.2 | 0.18.3 | RUSTSEC-2026-0594 |

RUSTSEC-2026-0459 (`libsqlite3-sys` 0.37.0, bundled SQLite < 3.54) is fixed only in
libsqlite3-sys 0.39 / rusqlite 0.41, which arti's `tor-dirmgr` 0.46 (rusqlite ^0.39) cannot use.
`scripts/audit-rust.py` therefore carries a scoped accepted risk: applied only when the lockfile
holds exactly libsqlite3-sys 0.37.0, depended on only by rusqlite, which is depended on only by
tor-dirmgr. The advisory needs a database + `-wal` pair written by a less trusted party; the only
SQLite files here are Tor's directory cache in the app's private storage. Any other shape blocks.

A new **early lockfile gate** (`Audit Rust dependencies early (lockfile gate)`) runs the same strict
audit right after patching and before the ~40 minute native build. Regression tests:
`scripts/test-security-ci.py` (31 cases).

## 5. Tests

- `SmartPlusPlanTest` rewritten (25 cases): identity families incl. both Gool modes, fair start,
  budgets by weight, light-to-heavy order, framing order (mobile / QUIC / Iranian SIM / SNI filtering),
  no forced ECH, memory, ladder interleaving, ranking and scoring, eligibility, ceiling.
- `ChainRepairTest`: the What's-new notice is 1.7.0 and keeps the troubleshooting steps.
- `PresetTransferTest`: exports tagged 1.7.0; unsupported versions rejected.
- Offline Python guards updated for version 1.7.0 / code 20.

Not done in the authoring environment: Gradle/Android build, on-device tests and a new field log.
The behaviour above should be confirmed with a Tehran log of 1.7.0 (look for `engine-serving`,
`Smart lane ... carries traffic`, `Smart: ranking ...`).

## 6. Same-version follow-up: the connect button that "does not respond"

Report: on a Galaxy S24 Ultra the connect button did nothing (1.6.0 and 1.5.0 alike, both APK
flavours, any display resolution) while the operator IP was shown; a tablet worked.

Root cause: a tap goes through `VpnService.prepare`. When it returns a consent intent and Android
refuses the consent without showing it, the consent activity finishes at once with
`RESULT_CANCELED`. AOSP's `ConfirmDialog` does exactly that when another package is the
**Always-on VPN** or `DISALLOW_CONFIG_VPN` is set (work profile, Secure Folder, MDM); a screen
overlay that blocks the *OK* button ends the same way after the user backs out. The callback in
`MainActivity` only handled `RESULT_OK`, so the app showed and logged nothing.

Fix (`MainActivity.kt`, strings in `values/` and `values-fa/`):
- every refusal sets an Error state, writes a Diagnostics line and opens a dialog with the cause
  (silent refusal < 1.5 s vs. dismissed) and an *Open VPN settings* button
  (`ACTION_VPN_SETTINGS`, falling back to wireless / main settings);
- a consent result that arrives in a recreated activity (no `pendingProfile`) connects with the
  current profile, or re-runs the normal connect path if the profile is not loaded yet;
- What's new: new bullet, notice revision `r2` (`AnnouncementStore.NEWS_REVISION`).

Not verified on a device in the authoring environment; Gradle was not available there.

## 7. Same-version improvements

| Area | What changed | Code |
|---|---|---|
| Auto-select | Fast first connect: background run, connect to the fastest server that already answered (15 s cap, 0.7 s grace), switch to the overall fastest when the run ends (20 ms / 10 % threshold) | `ConfigPingStore.startRun`, `AutoSelectPolicy.fastestSoFar`, `AetherVpnService.chooseFirstCustom` |
| Auto-select | Manual "Test all" switches the live session at once; network change (NOT_VPN networks, 3 s settle) re-measures | `ConfigPingStore.completed`, `UnderlyingNetworks` |
| Configs | One-tap ping / speed / delete; Speed test all; sort by ping, download, upload | `ConfigDataTable`, `ConfigSpeedTest.measureAll`, `ConfigCatalog.SortKey` |
| Home card | Latency, chain and traffic popups; per-app traffic via NetworkStatsManager | `ConnectionDetails.kt`, `PingStats`, `AppTraffic` |
| Settings | Unified chain builder mapping every chain to a backend or a ChainMode | `ChainBuilderPolicy`, `ChainBuilder.kt` |
| Diagnostics | Redacted report, clipboard + GitHub issue page, prompt after failure / crash, switch | `DiagnosticsReporter` |

Not verified on a device in the authoring environment; Gradle was not available. Unit tests added:
`ImprovementsTest`, `PingStatsTest`.

## Same-version update

**Smart anti-DPI brain, smart connection gate, damaged-identity fix (same version, 1.7.0 / 20)**

- **Smart brain (`core/DpiBrain.kt`).** Smart mode now reads the engine's own log while it
  connects and works out what the operator's DPI is doing: UDP silently dropped, TLS
  handshakes reset, gateways IP-filtered, the account API unreachable, a damaged identity.
  Both race lanes share what they learn, and the tactics still to come are re-planned on the
  spot - TCP carriers move ahead of UDP ones, TLS fragmentation and a stronger obfuscation
  floor are switched on, hopeless UDP rungs are abandoned after 7 s instead of their whole
  budget, equivalent tactics are never tried twice. What finally worked is remembered per
  network **and per operator** (a truncated hash, never the operator name), so a new network
  of the same operator starts with it. Nobody has to open a settings page.
- **"Verify connection" removed - replaced by the always-on smart gate (`core/LiveDataProof.kt`).**
  With the switch on, MASQUE never connected (the old gate demanded a geolocation answer AND
  device DNS over SOCKS5 UDP for up to 90 s); with it off, nothing proved that data flowed.
  Now every engine session (Aether, Psiphon, Tor and every chain) is reported Connected only
  after a real HTTP request crossed the tunnel and its answer came back - usually within a
  second. A bare SOCKS5 reply never counts, so a fake connection cannot pass. Device DNS over
  UDP is still measured for the diagnostics panel but no longer blocks. Race lanes are
  proven the same way. Custom configs pass the same smart gate - their switch was removed too, and an information card replaces it.
- **Root cause of "the engine stops 0.4 s after every start, on every protocol" fixed.** Field
  log (IR-MCI): `Error: Io(InvalidData, "stream did not contain valid UTF-8")`. Two identity
  seal passes could run at once (a second `stop()` of an already-reaped engine decremented
  the running counter of a live one) and one sealed the random bytes the other was shredding;
  from then on every start restored the noise and died. Fixed three ways: the vault is
  serialised and never seals or restores anything that is not UTF-8 text, an engine
  instance can release its running count only once, and the engine core itself
  (`config.rs`, `AETHER-APP-PATCH identity-not-utf8`) now quarantines a non-UTF-8 identity and
  provisions a fresh one instead of exiting. The brain heals a damaged identity and retries
  the same tactic once.
- **Faster Smart connects:** quality window 3.5 s → 2.5 s, route measurement 7 s → 4.5 s.
- What's new dialog: new bullets, notice revision `r5` (shown once more). Version stays
  1.7.0 / 20. Tests: `DpiBrainTest`, `IdentityVaultUtf8Test`, `test-functional-wiring.py`.

