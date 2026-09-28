# Changelog

## 1.4.0 — engine core 2.1.0, Psiphon inside the engine

App **1.4.0**, version code **16**, engine core **2.1.0**, `PATCHLEVEL` 1.4.0. Same signing
identity as 1.3.0, so it installs over 1.3.0 without an uninstall. File-by-file list in
`PATCH_NOTES_1.4.0.md`. 1.3.1 was never published on its own: every fix in the 1.3.1 section
below (built from the 1.3.0 field reports) ships in 1.4.0.

### Engine (core 2.0.0 → 2.1.0)
- Vendored core upgraded to 2.1.0; the app's engine patches rebased three-way.
- New engine patch `tor-only-psiphon-chain` (`--tor-only --psiphon`).
- New engine patch `psiphon-embedded-servers` (psiphon.rs, baseline added): the engine's
  Psiphon is started with the embedded server list (`assets/server_entries.txt`,
  `-serverList`), so a failed remote-list download never leaves Psiphon with no server.
- Build fix: the three-way merge of the gool UDP relay (`spawn_udp_forwarder`, `lib.rs`)
  left the 2.1.0 "follow the inner peer" block and the 1.2.8 bounded handoff interleaved
  (E0425 on `known` / `n`, and no `Err` arm on the receive `match`). Rebuilt as one loop:
  receive (stop on error), follow the peer under a short parking_lot lock, then the 20 ms
  bounded handoff.

### Psiphon runs inside the engine
- The app's own Psiphon (the AAR and its health watchdog) is removed; `Aether → Psiphon`
  and `Tor → Psiphon` run on the engine's built-in Psiphon (`libpsiphon.so`, the
  psiphon-tunnel-core console client).
- Fixed: both Psiphon modes could not connect. `libpsiphon.so` is a static GOOS=linux Go
  binary and finds no CA roots on Android, so the remote server list download failed with
  `x509: certificate signed by unknown authority`. New `core/PsiphonBootstrap.kt` exports
  the system CA store to a PEM bundle and passes it via `SSL_CERT_FILE`, `SSL_CERT_DIR`
  and Psiphon's `TrustedCACertificatesFilename` (config overlay, `AETHER_PSIPHON_CONFIG`).
- The `PsiphonTransport` readiness probe no longer logs `socksPeekByte() failed: EOF`.

### Smart routing: bypass Iranian sites, block ads (#66, #67)
- Two new routing switches, **Bypass Iranian sites** and **Block ads and trackers**, backed
  by built-in lists (`assets/routing/`) refreshed weekly THROUGH the tunnel
  (`core/SmartLists.kt`). The Iranian bypass is a direct rule, so it is suspended while
  sharing (zero leak), like every other direct rule.
- Engine sessions: the lists travel as a routes file (`AETHER_ROUTES_FILE`); ad names get an
  NXDOMAIN answer and an address -> name memory lets BLOCK rules catch flows that carry no
  server name (block only, never direct).
- Fixed (#66): in `Aether -> Psiphon` / `Tor -> Psiphon` no block or direct rule ever
  matched, because the engine only sees Psiphon's own server connections. The rules (the
  user's plus the built-in lists) are now applied in `PsiphonSocksFront`, where the
  destination is visible: TLS SNI / HTTP Host sniffing, DNS sinkhole, the same address ->
  name memory, DIRECT flows (TCP and UDP) sent out of the real uplink, BLOCK refused.
  Same grammar and precedence as the engine (`core/SmartRoutes.kt`, `SmartRoutesTest`).
  A session with no rules takes exactly the pre-1.4.0 path.
- In `Aether -> Psiphon` / `Tor -> Psiphon` stage 1 (the engine) no longer receives the user's
  block/direct rules: it only carries Psiphon's own server connections, where a direct rule could
  send Psiphon out without Aether. The front applies them instead. Tor modes unchanged.
- #67: with an h2 outer hop, the MASQUE-in-MASQUE inner hop tries QUIC first and falls
  back to h2 (`AETHER_MIM_INNER_QUIC=0` restores the old behaviour).
- The bundled Iranian CIDR seed is incomplete above 185.219.x; the full list is downloaded
  through the tunnel on the first connection that uses the bypass.

### Fixed: Gemini Live on home Wi-Fi drove ping past 1000 ms and stopped all data
- Root cause: uplink bufferbloat plus head-of-line blocking in Psiphon's single SSH
  connection; full analysis in `docs/UPLINK_SQM_1.4.0.md`.
- New `transport/UplinkGovernor.kt` (SQM): delay-gradient rate controller, token bucket
  and fair queueing with sparse-flow priority, applied to every byte `PsiphonSocksFront`
  hands to Psiphon (TCP relays and udpgw). Stays open on fat uplinks. Unit test
  `UplinkGovernorTest`.

### VPN sharing, rewritten (`core/ShareBridge.kt`, `core/ShareLeakGuard.kt`, `core/SharePages.kt`, `ui/SharePanel.kt`)
- Two modes, **Home Wi-Fi** and **Mobile hotspot** (Wi-Fi hotspot / USB / Bluetooth
  tethering), detected per interface and re-bound automatically when an address changes.
- Every tethering downstream is bound at once (hotspot + USB + Bluetooth). Downstreams are
  recognised by ROLE through ConnectivityManager (an interface that backs no upstream
  network), not by vendor name (`ap_br_wlan2`, `wlan2`, MediaTek `ap0` ...). Modem links
  (`rmnet`, `ccmni`, `seth` ...) are never shared; bogus IPv4 prefixes fall back to /24. A
  peer on any bound share subnet is admitted, nothing outside them; every address is shown
  in the Share card.
- Zero IP leak: the engine gets no Direct (bypass) rules while sharing (LAN refused if a
  running engine still has them); the bridge only ever dials loopback (fail closed); real
  SOCKS5 UDP ASSOCIATE relay through the tunnel; non-public, obfuscated-numeric,
  localhost and `.local` targets refused; identity headers stripped; fail-closed PAC at
  `/proxy.pac`.
- `http://aether.check/` is answered on HTTP proxy, HTTP CONNECT (443 refused at once so
  HTTPS-first browsers fall back), SOCKS5 CONNECT, and DNS over the SOCKS5 UDP relay
  (A → 198.51.100.254 sentinel, AAAA → empty NOERROR) so VPN/TUN clients can resolve it.
  `/check` on the phone address tests reachability directly.
- WebRTC: browsers send STUN outside an HTTP proxy (on home Wi-Fi it never reaches the
  phone; on hotspot/USB it goes through Android's tethering NAT, which an unrooted app
  cannot filter). The check/setup pages run a live WebRTC test against the tunnel exit;
  one-click locks are served under `/webrtc/` (Chrome/Edge/Brave/Chromium
  `disable_non_proxied_udp`, Firefox `proxy_only_if_behind_proxy`, macOS/Linux scripts);
  STUN handed to the share is carried through the tunnel.
- Optional username/password (off by default), editable or generated; RFC 1929 + HTTP
  Basic. Every address copyable (per row, selectable text, "Copy all"); PAC URL shown.
- Share pages redesigned: dark navy layout, language switch (Persian default, English),
  RTL throughout with every English token isolated LTR, Vazirmatn/Vazir via `local()` only
  with the system Persian font as fallback (nothing loaded from the network); plain
  step-by-step Persian, copy buttons, per-device proxy steps (Windows, macOS, Android,
  iPhone/iPad, Firefox, TV/whole device) and a guide for both share modes with the current
  one opened and marked. The password is never in a page.
- Share page hardening: CSP meta (`default-src 'none'`, no forms, no base), `no-referrer`,
  `esc()` for every outside value, WebRTC result built from escaped addresses.
- Diagnostics name the interface of the first device and warn after 90 s if no device has
  reached the share (no addresses logged). `ShareCredentials.ensure` attaches the bridge to
  the app context. Proxy mode: turning LAN sharing off keeps the loopback proxy running.
- Tests: `ShareLeakGuardTest` (DNS answer, check targets, STUN, role classification, lock
  files, bilingual pages, CSP, hostile host escaped).

### Zero Trust: sign in first, then connect (issue #12)
- `core/ZeroTrustWeb.kt`, `core/ZeroTrustSignIn.kt`, `core/TeamSignInRecord.kt`,
  `core/TeamMembership.kt`, `data/TeamSignInStore.kt` (`SecretStore.ACCESS_SIGNIN`).
- `core/TeamSignInHandoff.kt`: one decision shared by the service and the screen (no
  sign-in / other profile / already enrolled / expired / use).
- `ConnectionProfile.accessSignInToken` (never persisted, never in `ProfileCodec`); EMAIL
  mode passes `AETHER_ACCESS_TOKEN` instead of the address when set. Filled only in
  `AetherVpnService.hydrateSecrets`; spent or expired sign-ins are discarded there.
- `ui/settings/ZeroTrustMembership.kt`: membership status, sign-in dialog (FLAG_SECURE,
  3 attempts per code, 30 s resend cool-down, token re-read after sealing), sign out,
  remove membership (refused while an engine runs), service-token test.
- `TeamMembership` pattern covers `-secondary-lastconn`; `TeamSignInRecord.toString`
  redacts the token. en/fa strings updated.
- Tests: `ZeroTrustWebTest`, `ZeroTrustSignInTest`, `TeamSignInRecordTest`,
  `TeamMembershipTest`, `ZeroTrustEnvTest`. Details in `docs/ZERO_TRUST.md`.

### AI (Gemini)
- Default model is **Gemini 3.1 Flash-Lite** (highest free daily limit). A newly entered
  key starts on it; app-made picks follow it; a model the user chose is kept. Fallback:
  3.1 Flash-Lite preview, flash-lite-latest, then display order.
- The model picker shows a one-line description under each model. `SettingsChoiceRow`
  gained an optional `optionDescription` (other callers unchanged).
- Fixed: the chat never received the log. With "Analyse the log on every connect" ON, a
  fresh redacted digest (AiRedaction rules) travels with every chat request; when OFF, the
  model says access is off and where to enable it. The switch defaults to **OFF** (pref key
  `autoOptimize_v2`, so it applies to existing installs once).
- Answers render like Gemini: `ai/AiMarkdown.kt` (parser) + `ui/ai/AiRichText.kt`
  (renderer) for headings, bullets, numbered steps, bold/italic/code, quotes, tables and
  code blocks; no `*` or `#` reaches the screen; direction chosen per block by letter
  majority. Used in the chat, the explain sheet and the advisor; Copy gives clean text.
- Accuracy: grounding procedure and format rules in the prompts; the settings snapshot
  marks `[default]` / `[changed by user; default: X]`; live connection status in the chat
  prompt. Verify-and-revise: proposals that equal the current value, are invalid,
  duplicated or not writable are fed back to the model once and the answer is rewritten
  before display (chat + advisor). Chat temperature 0.6 → 0.35.
- Fixed: the assistant mark was half hidden in Persian (MessageRow clipped to an 18dp
  rounded rect). The tint is drawn with the shape instead of clipping content.

### Home screen and settings UI
- Fixed: the home screen (connect button + card) shrank on connect / resume.
  `FitToHeight` is two-way and deterministic (ideal factor derived every pass, 1 %
  hysteresis, overshoot ceiling, grow budget per epoch), ignores degenerate 0 px
  viewports, re-opens on ON_RESUME and on every connection-state change, and fails open
  after 450 ms so a window never measured with a usable height cannot leave a black screen.
- New `FitLineText`: status title, caption, pipeline and endpoint rows are one line in a
  slot whose height comes from the FONT (natural single-line height of a probe with the
  deepest Persian descenders and tallest Latin ascenders), so Persian descenders
  (ل, ر, م ...) are never clipped and every connection state has the same card height.
- `BaseRow` is a measured layout: a summary that would take 3+ lines beside a wide trailing
  slot goes full width under the row (fa + en).
- `SegmentedSelector`: every option is its own card, columns chosen by measuring labels
  (Protocol, IP version, Endpoint mode, Split mode). Home card: Endpoint row removed.

### Build and release pipeline
- New step **Enforce release tree** (first step after checkout,
  `scripts/enforce-release-tree.sh`): when a release is uploaded over an older one, every
  tracked file that is not in `SOURCE_MANIFEST.sha256` is removed and the cleanup is
  committed, so files left over from an older version can never break the build. It first
  verifies that every release file is present with the right hash and deletes nothing if
  the upload is incomplete; signing files and workflows are never deleted; it runs once per
  release (`.github/release-tree.applied`).
- **Free runner disk space** (after checkout), **Reclaim disk space (native build
  intermediates)** (after the engine build) and **Reclaim disk space before the Gradle
  cache save** keep the runner from running out of disk with the Tor-enabled engine, the
  Psiphon and lyrebird Go builds and Gradle in one job.
- `scripts/build-natives.sh psiphon` + CI step + APK check for `libpsiphon.so`.

### Security audit 1.4.0: 92 / 100
- `docs/SECURITY_AUDIT_1.4.0.md` (ten areas; the LAN share scored as its own area for the
  first time). Summary tables (English + Persian) in `README.md` and
  `.github/release-notes.md`.

## 1.3.1 — cold start, the widget, Zero Trust, the endpoint cache, Private Space, Android TV

App 1.3.1 / versionCode 15, same signing certificate, installs over 1.3.0 without
uninstalling. Engine core unchanged (2.0.0). `PATCHLEVEL` is `1.3.1`.

Everything in this release came out of the 1.3.0 field reports. No new transport,
no new protocol: nineteen things that were wrong, fixed at the cause.

### Fixed — the app took about ten seconds to open (#33, #30, #29, and the crashes)

ROOT CAUSE, and it was one bug behind four reports. `AetherApp.onCreate` called
`DiagnosticsLog.init(...)` on the MAIN THREAD, and that method read the whole
on-disk diagnostics log and decrypted it inline before returning.

The log is stored as a sequence of individually sealed records, and
`EncryptedLogFile.readLines` opens EVERY one of them. Each record costs one
`Cipher.init` plus one `doFinal` against a non-exportable Android Keystore key —
a binder round trip into keystore2 and an operation in the TEE, not an in-process
AES call. The log writer sealed one record per drained batch, and on an idle app
"a batch" is one line, so the file filled with roughly one record PER LINE: at the
512 KB size cap, thousands of records, thousands of keystore operations back to
back, before the first frame. Then `init` threw all but the newest 800 lines away,
so almost all of that work bought nothing.

That is the whole shape of the reports: "the app takes ten seconds to open", "it
opens slowly or stays on a black screen and won't open unless you clear data" —
clearing app data deletes this file, which is exactly why it worked — and "it gets
worse the longer I use it", because the cost grows with the file.

It is also why the `ForegroundServiceDidNotStartInTimeException` crashes kept
coming (#37, #26, #17, #16, #9): a main thread parked in the keystore cannot
answer `startForegroundService()` with `startForeground()` inside the framework's
ten-second window. 1.3.0-r2 added a handler that survives that exception; this
release removes the reason it was being thrown.

Four changes:

- `DiagnosticsLog.init` now returns immediately and does the restore on a
  background thread. Lines logged while it is still running are kept: the restored
  block is spliced in FRONT of them rather than clearing the buffer, so the panel
  reads in order either way.
- New `EncryptedLogFile.readLastLines(file, maxRecords)`. The record frame is
  length-prefixed, so the file can be WALKED without a key — read four bytes, skip
  that many, repeat — and only the tail that will actually be kept is decrypted.
  Same forgiving contract as `readLines`: a truncated or unopenable record ends
  the readable log rather than raising. The size-cap trim uses it too.
- The log writer LINGERS 250 ms for more lines before sealing a record, instead of
  draining only what was already queued. A trickle of lines becomes one record
  instead of one record each, which cuts both the write cost and everything the
  next start has to read back. 250 ms is below the panel's own publish interval,
  so the disk copy never lags the screen by more than one refresh.
- `SignerIdentity.logIdentity` moved off the main thread into the existing
  `aether-secure-init` thread. It makes a PackageManager call and SHA-256s a
  certificate, and the result is only ever a log line — nothing on the first frame
  was waiting for it.

Nothing about the security properties changed: the log is still sealed with
AES-256-GCM under the same non-exportable keystore key, still fails CLOSED if the
keystore refuses, and is still never written in plaintext.

### Fixed — the widget and the notification said "Disconnecting…" forever (#20, #23, #15)

Four separate bugs, all visible as one symptom.

- **The repaint that never happened.** `AetherWidgetProvider.updateAllWidgets` was
  called from exactly one place: `updateNotification()`. The disconnect path does
  not go through it — the notification is being dropped there, not updated — so
  after `stopEverything()` painted "Disconnecting…" and then flipped the state to
  `Idle`, nothing ever repainted the widget. The tile was refreshed on the line
  above; the widget was simply missed. It is now repainted on that transition, and
  in `onDestroy` as well, for the deaths that never reach `stopEverything`: task
  removal, a system kill, revoked VPN consent.
- **The widget read an in-process singleton.** `AetherController` is an `object`,
  correct only inside the process that owns the tunnel. An `AppWidgetProvider` is a
  `BroadcastReceiver`, and `APPWIDGET_UPDATE` after a reboot or a launcher restart
  is delivered into a freshly forked process where that flow still holds its
  initialiser. New `widget/WidgetStateCache` persists the state name (nothing
  identifying — no endpoint, no exit IP, no profile) and applies one rule on a cold
  read: **a transient state does not survive a process death.** `Connecting`,
  `Verifying`, `Launching`, `Reconnecting` and `Disconnecting` all describe work
  some thread was doing; if the process is gone the work is gone, and the honest
  answer is `Idle`. No timer, nothing to expire.
- **The notification's action button was labelled with a state.**
  `addAction(..., R.string.state_disconnecting, ...)` — so the ongoing notification
  read "Disconnecting…" / "در حال قطع…" the entire time the tunnel was UP, which is
  precisely what #20 describes. New string `notif_action_disconnect`
  ("Disconnect" / "قطع اتصال") in both languages. A button is an instruction, not a
  state.
- **An orphaned notification.** `updateNotification` posts through
  `NotificationManager.notify()`, but `stopForeground(STOP_FOREGROUND_REMOVE)` only
  removes the notification the service currently holds the foreground WITH. A
  disconnect arriving after the foreground was already dropped left that
  "Disconnecting…" notification belonging to nobody. `stopForegroundCompat` now
  cancels the id explicitly.

### Changed — the widget is 1×1 and shows its state by colour (#23, #15)

"widget is 3*1 (big!) but 1*1 is enough" and "please make it smaller". It was
`targetCellWidth=3` / `minWidth=180dp`, and the width was being spent on an
app-name title the launcher already shows next to the widget.

Now a 1×1 widget (40dp) that can still be RESIZED in both directions up to the old
shape for anyone who preferred it. The title is gone; the power icon is tinted by
connection state via `setColorFilter`, the same colour the status line carries, so
on/off is legible without reading anything — which is what #15 asked for.
`updatePeriodMillis` stays 0: repaints happen on real state changes, so the system
never wakes the app on a timer.

### Fixed — wrong operator on dual-SIM phones (#7)

`TelephonyManager.networkOperatorName` answers for the manager's OWN subscription,
and the manager from `getSystemService` carries the DEFAULT one — usually SIM 1,
regardless of which SIM carries data. A phone with IR-TCI in slot 1 and Irancell as
the data SIM was fingerprinted as `operator="IR-TCI"`, so Smart Auto built its
strategy ladder for the wrong carrier.

`SmartAuto.readOperator` now asks for the manager bound to the subscription that is
actually carrying data: `getActiveDataSubscriptionId()` on API 30+, otherwise
`getDefaultDataSubscriptionId()`, then `createForSubscriptionId()`. Both ids are
static on `SubscriptionManager` and need no runtime permission — unlike the
subscription LIST, which needs `READ_PHONE_STATE` and is deliberately not used. If
no id is valid (no SIM, Wi-Fi only) the default manager is used exactly as before,
and every call is wrapped so an OEM build that reports an unusable id leaves the
fingerprint working rather than failing the connect.

### Added — x86_64 builds (#8)

Chromebooks and Android emulators are x86_64. With no such split the APK either
refused to install or ran the engine through ARM translation. `x86_64` is added to
`abiFilters`, to the ABI split, to `ABIS` in `scripts/build-natives.sh` (which
carries hev, the JNI bridge, the engine and the pluggable transports with it), to
the Rust target list in CI and to the release artifact map. Its versionCode offset
is 4, APPENDED rather than inserted, so the codes already published for the three
existing ABIs do not move — a versionCode that goes backwards is an update that
can never be offered to the users who already have it. The Psiphon AAR already
ships `jni/x86_64`, so the chained mode works there too.

### Fixed — the Tor feature check could fail a good build (#48)

`scripts/build-natives.sh` runs under `set -euo pipefail` and verified the Tor
feature with `strings … | grep -q -- '--tor-bind'`. `grep -q` exits on the first
match, closing the pipe under `strings`, which then dies of SIGPIPE; `pipefail`
promotes that to the pipeline's status, and the build fails claiming Tor is missing
from a binary that contains it. A false negative, and timing dependent, so it
fired on some runners and not others.

Fixed by letting grep read to the end and discarding its output, which keeps the
exit status meaningful. Reported with a patch that wrote
`grep -- '--tor-bind' />/dev/null`; that one does not work — the shell splits it
into an argument `/` plus the redirection, so it greps the root directory and never
looks at the binary. The form used here is `| grep -- '--tor-bind' >/dev/null`.

### Fixed — `Tor → Aether` never connected, in 1.3.0 or 1.3.1

A field log settled this one. **Tor was never the problem** — it bootstrapped
perfectly on all three attempts:

```
tor reaching the network: 100%: connecting successfully
[+] tor reaching the network: the way out is open
[+] tor is ready; the tunnel goes out through 127.0.0.1:1820
```

What failed was the endpoint SCAN, run THROUGH that Tor proxy:

```
[*] scan mode=turbo ip=ipv4 candidates=896 ports=[443,500,1701,4500,4443,8443,8095]
    concurrency=20 per_probe=6s budget=45s
[+] dialling out through the socks5 proxy at 127.0.0.1:1820
[-] scan deadline reached with no gateway
```

896 candidates across seven ports, twenty at a time, in 45 seconds, with every
probe a fresh Tor circuit. Tor exit policies do not even permit most of those
ports. And the two `/24` ranges came from a DPI fingerprint measured on the DIRECT
path, so they describe what the phone can reach, not what a Tor exit can. Three
rungs, three bootstraps, no gateway, and the engine's SOCKS5 port never opened.

**The bug was a comment that described an intention the code did not implement.**
`connectFlow` says the reverse chain's budget and transport "are decided in
connectAetherStage's plan" — and that plan does hold the right rung. But
`connectAetherStage` is only ever reached from `connectExternal`, the chained
Psiphon path, which this backend is not. So with `protocol=AUTO` the reverse chain
fell through to `connectSmartAuto` and got the ordinary four-rung scan ladder.

New `reversePlan()`: ONE candidate, because through Tor there is nothing to scan
for — the engine dials one endpoint out through the SOCKS proxy.

- **MASQUE over HTTP/2**, the only carrier core 2.0.0 accepts here (Tor is
  TCP-only, WARP's WireGuard endpoints are UDP-only). This is also why the log read
  `Attempt 1/4 → WIREGUARD` while the command line said `--masque`.
- **`ScanMode.TURBO`**, the narrowest budget the engine has, so it commits to the
  endpoint Cloudflare assigns instead of hunting.
- **Manual ranges cleared** (`endpointMode`, `manualRange`) — see above for why
  directly-measured ranges are worthless behind a Tor exit.
- **`torBudget()`**, minutes rather than the ladder's 60 s, which expired while Tor
  was still fetching a directory.

The backend did not have to be removed, so plain Psiphon was not substituted for
it. **Not yet confirmed on a device** — the change is reasoned from the log and
compiles, but only a handset on a real network can prove it connects.

### Fixed — the About card was English even in Persian

Two feature lists were hardcoded as Kotlin `listOf(...)` in `ui/AboutPanel.kt`, so
they bypassed `strings.xml` entirely and stayed English whatever the app's language
was. One of them even carried a comment saying it was "deliberately English-only,
mirroring the upstream README" — overruled on request, because a Persian user
reading an all-English card cannot tell the two projects apart, which is the one
thing that card exists to do.

Both are now `<string-array>` resources (`about_original_features`,
`about_port_improvements`) read with `stringArrayResource`, with a fluent,
plain-spoken Persian translation rather than a literal one. Technical proper nouns
— MASQUE, WireGuard, SOCKS5, VpnService, Zero Trust — are kept as they are and
carry BiDi isolates, so they read left-to-right inside a right-to-left line.

Right alignment needed no change: `FeatureList` renders each item as a `Row`, and a
`Row` follows the layout direction, so the bullet moves to the right side by itself.
`scripts/fix-fa-bidi.py` was extended to cover `<item>` inside `<string-array>` and
re-run.

### Fixed — Persian menus were not right-aligned and mixed lines were scrambled

Reported from a device with a screenshot of **Transport & anti-DPI**. Two separate
BiDi defects, and neither was about the layout code.

**1. Wrong paragraph direction.** Android resolves a text paragraph's direction
from its FIRST STRONG character. A Persian string that begins with a Latin word —
`"MASQUE روی HTTP/2"`, `"Keepalive (ثانیه)"`, `"Zero Trust (سازمانی)"` — is
therefore laid out left-to-right *as a whole*, which is why those rows sat
left-aligned between right-aligned neighbours. Eleven strings were affected.
Prefixing them with U+200F RIGHT-TO-LEFT MARK makes the first strong character RTL.

**2. Neutral absorption.** Inside an RTL paragraph the Unicode Bidi Algorithm shows
a Latin run left-to-right correctly, but the neutral characters touching it —
brackets, slashes, colons, commas, full stops, digits — have no direction of their
own and get absorbed into whichever run wins. That is what moves a closing bracket
to the wrong end and strands a full stop at the start of a line. Each Latin passage
is now wrapped in U+2068 FIRST STRONG ISOLATE … U+2069 POP DIRECTIONAL ISOLATE:
laid out LTR internally, with the text around it keeping the paragraph direction.
102 strings.

Done by `scripts/fix-fa-bidi.py`, which is committed so it can be re-run after any
translation change, and is idempotent. **One island per PASSAGE, not per word** —
the first attempt isolated each word and that was worse: two adjacent LTR islands
with a neutral space between them are reordered by the RTL paragraph, so
`"Zero Trust"` rendered as `"Trust Zero"`. Format specifiers (`%1$s`, `%d`) and
escapes are skipped; a value that is purely Latin (`MASQUE`, `IPv4`) is left alone,
because forcing RTL on a word that reads LTR either way would be wrong.

Verified two ways: stripping the three invisible characters back out yields a
file byte-identical to the original — so no word, digit or mark was added, removed
or moved — and `aapt2 dump resources` confirms the marks survive into the packaged
APK.

### Fixed — the home screen clung to the top of tall screens (1.3.0 report)

*"The on/off button used to sit in a better position and the connection info used
to scale to the full screen; now the home screen looks out of order."*

`FitToHeight` measures the home screen's natural height and, when the viewport is
smaller, shrinks the whole subtree by one factor so nothing needs scrolling. It
then placed the result with `place(0, 0)` — **top-aligned**.

So the behaviour split by device, which is why the report was hard to act on:

- On a **short** screen the block is shrunk to fit exactly, fills the height, and
  looks right.
- On a **tall** screen the content is shorter than the viewport, the factor stays
  1, and the whole block was pinned to the top with every pixel of slack dumped
  below the connection card — a high button and a half-empty screen.

Persian made it more likely either way: more strings wrap to two lines, so the
natural height is greater. 1.3.0 also added the Tor rows, which is why a 1.3.0
user noticed what a 1.2.x user did not.

The block is now centred in the height it is given. When it had to be shrunk the
offset is zero and nothing changes; when it did not, the button comes off the top
edge. An overflowing block — below `minFactor` — still anchors top-left.

### Not a defect — Android TV is not a setting (#21)

A user went looking for it in the app's settings and found nothing. Correct: there
is nothing to find. TV support is three manifest declarations — `touchscreen` and
`leanback` marked not required, plus the `LEANBACK_LAUNCHER` category and a banner
— which decide whether the APK may be INSTALLED on a TV and whether the TV's
launcher LISTS it. There is no switch, and there is no TV-specific screen: the
Compose UI is the phone one. Verify it by installing on a TV, not by looking in
Settings.

### Fixed — a working endpoint was thrown away on every reconnect (user report)

*"It takes forever to find a healthy IP, and the moment the connection drops it is
thrown away. It should be saved, the way the original core does it."*

The core does save it — `lastconn.rs` plus `want_quick_reconnect` have always
worked. What discarded it was one of THIS app's own engine patches,
`AETHER-APP-PATCH quick-reconnect-rtt-budget`, together with the numbers the app
was feeding it.

`fastEndpointOnly` defaulted to ON, sending budgets of 320 ms / 1400 ms direct and
180 ms / 900 ms chained, chosen "above the good edges observed on Iranian mobile
(100-150 ms)". The 1.3.0 field logs contain no such edges:

- a cached WireGuard endpoint rejected at `rtt 1.272s` against the 180 ms budget;
- a cached MASQUE gateway that verified as WORKING, rejected at `handshake 2.778s`
  against the 1.4 s budget — after which a `thorough` scan ran for 300 s, found no
  gateway at all, and the connect FAILED. That session ended with nothing, having
  held a working gateway seconds earlier;
- a scan whose every accepted candidate sat between 727 ms and 1.8 s.

So the option was never choosing the faster of two endpoints. It was discarding the
only one there was, and buying a full scan on every single reconnect.

- `fastEndpointOnly` now defaults to **false**, in `ConnectionProfile` AND in the
  `ProfileStore` fallback — both were needed, or a fresh install would have kept the
  old behaviour. Unset, the engine is back to its own rule: reuse a cached endpoint
  that answers. Throughput on a good link is what is traded away, and that is the
  right way round — a slow tunnel is usable, a missing one is not. A user's explicit
  choice still wins.
- The budgets it sends when switched ON are recalibrated to 2000 / 5000 direct and
  1200 / 3500 chained, above what those logs actually show, so the option rejects an
  endpoint that has become genuinely bad instead of every endpoint that exists.

**An engine-side half is NOT applied.** On the MASQUE path, going over budget sets
`quick_peer = None` and nothing remembers the gateway, so a scan that finds nothing
strands the session — the second bullet above. (The WireGuard path is already
protected: `wg_prober::set_rtt_floor` / `apply_rtt_floor` returns the cache when the
scan cannot beat it. MASQUE has no equivalent.) The fix is
`patches/0001-masque-quick-reconnect-budget-fallback.patch`: keep the demoted peer
and use it if `hunt_masque_peer` fails. It applies cleanly (`patch --dry-run`) but
is **not applied and not compiled** — building that crate needs Rust 1.98 and a
BoringSSL compile, which was not available, and every other change in 1.3.1 was
built and tested. Apply it where cargo exists.

### Fixed — Zero Trust: the e-mail code path did nothing at all (#12)

The reporter had the diagnosis exactly right: *"no code arrives and the app never
asks for one — this section is simply ignored"*, and *"you cannot do the
authentication inside the connection; it has to be signed in beforehand."*

The app treated enrolment as part of connecting: it put `AETHER_ACCESS_EMAIL` into
the engine's environment and started a tunnel. Cloudflare mailed a code, the engine
blocked waiting for someone to type it, nobody was listening, and the session died
with the reason in a log line no ordinary user reads.

Core 2.0.0 was built for exactly this parent. `zerotrust::prompt_login_code` tests
`stdin().is_terminal()`, and when stdin is NOT a terminal — which is how this app
spawns the engine — it writes one machine-readable line to stdout:

```
[zerotrust] login-code-needed attempt=1 email=someone@example.com
```

and then reads a line from **stdin**, bounded by `CODE_WAIT` (300 s) and retried
`CODE_ATTEMPTS` (3) times. The whole protocol is: watch for that line, ask, write
the code back. No JNI, no engine change. The app had simply never looked for it, and
had never written a byte to the engine's stdin.

- New `core/LoginCodePrompt.kt` — recognises the marker, parses `attempt` and
  `email`, publishes a `StateFlow`, and writes the code with the newline the engine's
  `read_line` is blocked on plus an explicit `flush()`. The code itself is never
  logged: it is a single-use credential.
- `AetherProcess` hands the child's `outputStream` over on spawn, calls `ingest` in
  the stdout loop it already had, and detaches when the engine exits, so a dialog
  cannot outlive the process that asked for it.
- New `ui/LoginCodeDialog.kt`, hosted at the top of the composition in
  `MainActivity` because the prompt can arrive on any screen. Tapping outside does
  not dismiss it — that is how a one-time code gets lost. "Not now" writes nothing
  and says so in the log: the prompt belongs to the engine, which keeps waiting
  until its own timeout, and claiming the app can cancel it would be untrue.

**The criticism still stands in part.** This is authentication DURING a connect,
not "sign in, then connect". A real pre-login is possible — `ffi.rs` exports
`aether_team_code_request` / `_resend` / `_submit` / `aether_team_sign_in`, which
are precisely a sign-in flow decoupled from any tunnel — but the app cannot call
them: it runs the engine as a child PROCESS, and those are a C ABI in the same
`.so`, reachable only from a JNI binding built against the NDK. The design, and
what already exists for it, is in `docs/ZERO_TRUST.md`.

### Changed — the enrolment token is checked as you paste it (#12)

The token path is the one that does match "signed in beforehand": sign in at
`https://<team>.cloudflareaccess.com/warp`, copy the value after `token=`, paste it.
Its two natural mistakes — pasting the URL instead of the token, and pasting
something that expired — used to become a failed connect whose reason lived in the
diagnostics log.

New `core/AccessToken.kt` mirrors the two checks the engine makes
(`looks_like_jwt`, `jwt_expired`) so the verdict appears under the field: malformed,
expired, or valid with the days remaining. Covered by `AccessTokenTest`, 14 cases,
including the URL paste, `exp` exactly now, a missing `exp`, a non-JSON payload and
a 25-digit `exp`. It uses `java.util.Base64` rather than `android.util.Base64`
deliberately — the project's tests are plain JUnit and the Android one is a stub
that throws off-device.

It is **not** verification. The signature is not checked and cannot be; the key is
Cloudflare's. The engine remains the only thing that decides whether a token is
accepted.

The settings screen also now says which of the three methods needs the user
present: the service token does not, the e-mail code does and will interrupt a
connect to ask.

### Added — Android TV (#21)

Nothing about the tunnel was stopping this. Two declarations were stopping the APK
from being installable and findable on a TV:

- Android treats `android.hardware.touchscreen` as implicitly REQUIRED, and a TV
  has none — so the app was filtered out of TVs entirely. It is now declared
  `required="false"`.
- A TV launcher only lists activities in `android.intent.category.LEANBACK_LAUNCHER`.
  `MainActivity` has it now, with an `android:banner` (320×180, `drawable-xhdpi`).

`android.software.leanback` is declared NOT required on purpose: one APK has to keep
installing on phones, which do not have it. Verified in the packaged manifest with
`aapt2 dump xmltree`.

**The honest limit:** this is a compatibility declaration, not a TV build. The
Compose UI is unchanged and still touch-shaped; D-pad focus traversal has not been
designed for. Declaring leanback also switched on lint's TV checks, which is where
two of the three new warnings come from — one of them, `LeanbackUsesWifi`, is in the
Psiphon AAR's own manifest and cannot be fixed from here.

### Added — type an MTU instead of picking one (#33)

*"Let the MTU be chosen freely, not by clicking between options."* Correct: path MTU
is a property of a network, and a user hunting for the value that stops Telegram
stalling needs to enter it.

A numeric field joins the presets, which stay — they are the values that matter for
most people. Bounds are `MTU_MIN = 1280` and `MTU_MAX = 9000`: 1280 because it is
IPv6's minimum link MTU (RFC 8200 §5) and this app routes `::/0` unconditionally in
its chained and lockdown modes, 9000 because the existing 8500 preset has to stay
reachable by hand.

The field owns its own text and only commits a value inside that range. A partially
typed number — "12" on the way to "1280" — is not an MTU, and handing one to the
engine builds a TUN that connects and carries nothing.

### Fixed — the Gemini API key message named the wrong cause (#31, #11)

*"The app has the old Gemini and does not support the new format."* The cause is
real but it is not in the app's request shape: Google retired the old **standard**
Gemini API keys during 2026 — unrestricted ones stopped working on 19 June 2026 and
all standard keys are rejected from September 2026. Keys created in AI Studio since
then are "auth keys" bound to a Cloud service account.

So a key that used to work and now does not is far more likely to be retired than
mistyped, and `ai_error_bad_key` said only "check it and try again". It now names the
retirement first, in both languages.

**The transport was already correct and was deliberately left alone**: the
`x-goog-api-key` header on `generativelanguage.googleapis.com/v1beta` is still
Google's documented form, and `models/<id>:generateContent` still works. Changing it
would have been changing something that is right.

`gemini-3.7-flash` (shipped 2026-08-13) was missing from `AiModelPolicy.ALLOWED` and
is inserted at rank 2. That renumbers the labels after it, so
`AiModelPolicyTest` was updated rather than the insertion walked back: the number is
a label in the model picker and the SELECTION is stored by id, while rank order is
what the file exists for and has to follow the models.

### Added — paste your own ECHConfigList (#44)

New `ConnectionProfile.echConfig`. Empty means `--ech auto`, exactly as before;
a value is passed to the engine instead. It is checked as plausible base64 first,
because the engine's answer to a bad value is `[-] bad AETHER_ECH: …; continuing
without ECH` — one line in a log nobody reads, leaving the user believing ECH is on.
The field only appears while the ECH switch is on.

**Not what was asked for, and the request cannot be met here.** #44 asked for
v2rayNG's shape: name a *different* domain (`gitlab.io+https://8.8.8.8/dns-query`) so
the DPI sees a handshake for a host it does not filter. The engine's `--ech` takes
`auto | <base64>` and nothing else — `auto` fetches the list for the hosts in
`dns::ECH_HOSTS`. Naming an arbitrary domain means the engine resolving an HTTPS RR
for it, which is engine work. What ships is the half that exists, and the UI says so.

### Added — a prompt when Android is still allowed to suspend the app (#5)

*"When I turn the screen off and on again the connection drops — the VPN still says
connected but nothing loads."* On most vendor builds that is Doze / app standby
suspending the process the tunnel lives in, and only the user can grant the
exemption.

A settings row appears **while the exemption is missing** and opens the system's own
battery-optimisation list. It uses `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`,
which needs no permission, rather than `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`,
which would have added `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` to a manifest the
audit counts at five permissions. One extra tap was the better trade.

### Verified, not changed — per-app tunnelling was already there (#3)

`SplitMode.INCLUDE` / `EXCLUDE` in `ConnectionProfile`, the picker in
`ui/components/AppPickerDialog.kt`, and `AetherVpnService` calling
`addAllowedApplication` / `addDisallowedApplication` — plus a separate userspace
filter for `blockedApps`. The request dates from 22 July and a 1.2.x build. No code
was written; the issue can be closed.

### Fixed — Private Space: the second instance could not connect (#27, #52)

Running Aether in Android's Private Space (or a work profile) while it was already
connected in the Main Space failed for the second instance, on every protocol and
every fallback strategy, and failed BEFORE any tunnel was attempted:

```
the socks5 listener cannot use 127.0.0.1:1819:
Address already in use (os error 98); another program already listens there
...
Engine exited before it opened the SOCKS5 port.
```

Private Space runs the app as a separate Android user but **shares the network
namespace**, and every local port in `core/TunnelConfig.kt` was a `const` that the
app bound unconditionally — 1819 engine, 1820 Tor, 1821 Tor front, 1825 chain,
1827 Psiphon. Two instances reached for the same five numbers and the second lost.

The engine already accepted `--bind <addr>` and `--tor-bind <addr>` (core 2.0.0
`cli.rs`, also `AETHER_SOCKS` / `AETHER_TOR_BIND`). The app never passed either and
relied on the built-in default, which is where the fixed 1819 came from. So no
engine change was needed.

New `core/PortLease.kt` resolves the five ports once per session, and the rule is
**preferred first**: 1819/1820/1821/1825/1827 whenever they are free, so a normal
single-profile install behaves exactly as before and the documented Proxy-only
address does not move. Only a busy port is stepped over, in tens, with the five
guaranteed distinct. `Profile.toArgs()` now always emits
`--bind 127.0.0.1:<port>` — even when it names the default — so the log states the
port instead of leaving it implied.

Everything else reads `PortLease` instead of the constants:
`TransportBackend.exposedSocksPort` / `torSocksPort`, `ExternalTransport`,
`PsiphonTransport`, `ShareBridge`, `Diagnostics`, `PingMonitor`, `MainActivity`,
and the three `const val` in `AetherVpnService`'s companion became accessors — which
is why the twenty-odd use sites inside that file did not have to change.

**What this is not.** It is not a lock. Between the probe that finds a port free and
the engine binding it there is a time-of-check / time-of-use gap, and a bound socket
cannot be handed to a native child process here. What is gone is the CERTAIN
collision of two instances insisting on the same number; what remains needs two
processes to pick the same free port in the same instant. If nothing free is found
the preferred port is kept, so the worst case is the old behaviour rather than a
refused connect.

**LAN sharing is NOT covered.** `ShareBridge` binds 10810/10811 and retries the same
port rather than moving, so a second instance still cannot share over the LAN. That
is left alone deliberately: those are addresses users type into a PC or a TV, and
moving them silently is worse than a clear failure. Issue #28 asks for them to be
*configurable*, which is a different change. What was fixed is that the two copyable
rows in **Settings → Apps** now show the port the bridge is actually on instead of
printing the constant — the Share card on the home screen already did, so the two
screens could disagree, and this was the one with a copy button.

### Fixed — `lint` could never pass (pre-existing)

`res/values/themes.xml` declared `android:windowLayoutInDisplayCutoutMode`
unconditionally. The attribute exists from API 27 and this app's `minSdk` is 26,
so it was the project's single `lint` ERROR — `[NewApi]`, on that line. Harmless
at runtime, because the framework ignores an attribute it does not know, but a
check that always fails is a check nobody reads. Nothing in CI runs `lint`, which
is why it survived.

The style is now overridden in `res/values-v27/themes.xml`, which is how the
resource system does this: a qualified folder REPLACES a resource rather than
merging into it, so the three items above the cutout mode are repeated there and
the two files have to be kept in step. Verified in the packaged APK: the base
style carries three items, the `(v27)` variant four.

Not from this release's work — it was in 1.3.0 exactly as shipped.

### Fixed — the connection row named the second hop twice (field report)

On every chained mode the home screen's connection row read

```
Aether(WIREGUARD → PSIPHON) → Psiphon
Aether(WIREGUARD → TOR) → Tor
```

instead of `Aether(WIREGUARD) → Psiphon`. The bracket is supposed to hold the
engine's transport and nothing else.

The defect was not in the label but in what was handed to it. Both chained paths
in `AetherVpnService` published the whole chain through `EngineMeta.setProtocol`:

```kotlin
EngineMeta.setProtocol("${stageProfile.protocol.name} → ${profile.backend.externalKind?.name}")
EngineMeta.setProtocol(if (usesWarp) "${engineProfile.protocol.name} → TOR" else "TOR")
```

That was the right shape before 1.3.1, when this value WAS the row and there was
no other way to say a second hop existed. Since 1.3.1 the row asks
`TransportBackend.pipelineLabel(transport)` for the path and puts this value in
the brackets, so the composite string produced the arrow and the exit twice.

Both callers now publish the transport alone. `pipelineLabel` additionally keeps
only the part before an arrow, so a future caller cannot bring the doubling back;
`TransportBackendLabelTest` pins both halves across all six modes. `EngineMeta`'s
protocol field has exactly one consumer (`ConnectionCard`), so nothing else
changes shape.

### Fixed — the endpoint row was empty (field report)

The info row's endpoint showed an ellipsis on a working connection. `EngineMeta`
recognised two engine log lines, and both are printed by the SCAN and by nothing
else:

```
[+] selected WireGuard endpoint 162.159.195.96:946 (rtt 412ms)
[+] selected MASQUE gateway 162.159.198.1:443 (rtt 88ms)
```

Every other way the engine arrives at a peer is silent as far as those two
patterns go — the cached reuse (`[+] cached gateway … still works; skipping
scan`), a retry of the last known-good gateway, a hand-pinned peer.

This is why it showed up now and not in 1.3.0: this very release stopped
discarding a working cached endpoint (the quick-reconnect RTT budget is off by
default now, see below), so the reuse path became the ordinary one on every
reconnect — and the reuse path prints no `selected …` line.

The fix reads the line the engine prints on EVERY path instead, in `run_masque`,
`run_wireguard` and `run_warp_in_warp` alike, right before the tunnel comes up:

```
[+] using cloudflare edge 162.159.198.1:443
[+] using cloudflare edge 162.159.198.1:443 (outer) and 162.159.192.1:443 (inner)
```

MASQUE-in-MASQUE has no such line and announces itself once both hops are up, so
it gets a second pattern (`masque-in-masque ready: …`). Both capture the OUTER
peer — the address the device actually dials, which is what the row names. The two
scan patterns stay. `EngineMetaTest` holds every one of these strings, copied from
the engine's own `log::info!` calls, so an engine bump that renames one fails in
CI instead of on a phone.

### Fixed — Persian text under a technical field was left-aligned (field report)

Reported on the MTU helper text: `هر مقداری بین ۱۲۸۰ و ۹۰۰۰. اگر تونل بالا است ولی
بعضی سایت‌ها یا تلگرام باز نمی‌شوند، کمترش کنید.` rendered left-aligned with the
full stop at the wrong end.

Root cause, and it applied to EVERY technical field in the app rather than to that
one string: `LtrOutlinedTextField` wrapped the whole `OutlinedTextField` in

```kotlin
CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr)
```

That island was there for the VALUE — an `ip:port` must not be reordered by the
BiDi algorithm — but it took the decorations with it, and `label`, `placeholder`
and `supportingText` are Persian prose. Inside an LTR paragraph the trailing full
stop of a Persian sentence is a direction-neutral character that resolves to the
paragraph direction, so it was laid out at the right-hand end and the whole block
sat left-aligned among right-aligned neighbours.

The layout-direction island is gone; the field follows the app's language like
every other row. The value is unaffected, because what governs the reordering of
the field's CONTENT is the paragraph direction of its text —
`textDirection = TextDirection.Ltr` in the text style — not the layout direction
of the box around it. `textAlign` moved from `Start` to `Left` so the value stays
visually where it has always been instead of flipping to the right edge.

Checked, and deliberately not changed: `scripts/fix-fa-bidi.py` was re-run over
`values-fa/strings.xml` and reported 0 strings changed, so the resources were
already correct and this was purely a rendering defect. After this fix the only
`LocalLayoutDirection` provider left in the app is the one in `AetherTheme`, which
is pinned from the stored language choice and which `Dialog`, `Popup`,
`DropdownMenu` and `ModalBottomSheet` inherit.

### Changed — each option in a choice sheet is its own card (field report)

`SettingsChoiceRow`'s bottom sheet — the one every picker in the app opens:
obfuscation, exit country, scan mode, IP family, keepalive, Tor mode — stacked
bare rows directly on the sheet background with nothing between them, which reads
as one block of text rather than as a set of separate, tappable things.

Every option is now a card in the same visual language as the rest of the
settings: rounded corners, the `Navy850` surface, a hairline border, 8 dp between
cards, a 54 dp minimum height. The selected one is tinted, outlined and
semi-bold in the primary colour as well as ticked, so the current value is
visible from the shape of the list and not only from one glyph. Still a
`LazyColumn`, because the exit-country picker is 56 rows.

### Added — Smart races two routes and remembers what worked on this network

Smart (`Protocol.AUTO`) now has two implementations and picks between them itself:
a two-lane RACE where the backend allows it, and the ladder it always had
everywhere else. The hand-picked protocols are untouched.

**This shipped once as a separate selector entry called "Smart Plus" and was
withdrawn in the same release.** Two reasons, both found on a phone:

* The split was invisible where it mattered. A user selecting "Smart Plus" on
  `Aether → Psiphon` got the Smart ladder, silently, because a race needs two
  engine processes with two identity files and two ports and a chained stage-1
  cannot give it those. A mode that behaves like another mode on most of what the
  app offers is a worse answer than no mode.
* Worse, it could not connect there at all. `connectAetherStage` chooses its plan
  with `stage.protocol == Protocol.AUTO -> SmartAuto.buildPlan(...) else ->
  directPlan(stage)`, so `AUTO_PLUS` fell through to the HAND-PICKED path and
  `toArgs()` emitted no protocol flag for it — an engine command line with no
  `--wg`, no `--masque`, nothing. Field report: "with Smart Plus and Aether +
  Psiphon it does not connect at all, with Smart it connects easily."

The capability was never a mode; it is a property of the backend. `Protocol.AUTO`
now asks `SmartPlusPlan.eligible(profile)` and the settings screen says which of
the two this profile will get.

**What Smart does today.** `SmartAuto.buildPlan` builds a ladder of four rungs and
`runLadder` walks it one rung at a time, each with its own timeout: three TURBO
attempts at 60 s plus a last resort on the user's own scan mode. On the networks
this app exists for that is up to `60 + 60 + 60 + 150 = 330 s` before the user is
told anything — the shape of the field log in report #47, where four attempts
failed over about five and a half minutes.

**What Smart Plus adds.** Two things, and they work together:

1. **Per-network memory** (`core/SmartPlusMemory.kt`, keyed by
   `core/NetworkIdentity.kt`). The route that carried traffic on THIS network is
   tried first next time on this network, as one 45 s attempt, before anything is
   raced. A success is trusted for 14 days, a failure for 6 hours, and at most 32
   networks are kept.
2. **A race** (`core/SmartPlusPlan.kt`). Independent strategies run side by side
   and the first one that carries real outbound TCP wins. Worst case drops from
   330 s to the race ceiling of 187 s, and the ordinary case is one lane
   connecting in seconds.

#### Two lanes, not five — the one place a straight port would have broken the app

The reference implementation (WhiteAesther 1.9.3, `data/AutoRoute.kt`) races three
CARRIERS — its engine, Psiphon, Tor — and its own comment states the rule:
*"different carriers can be tried at once, while two routes of one carrier cannot:
there is one engine and one tor."* Aether has one carrier and five protocols, so
"race the protocols" looks like the translation. It is not, because of what the
engine keeps on disk (`native/aether/aether/src/lib.rs`):

```
 --wg     -> aether.toml         + aether-lastconn.toml
 --gool   -> aether.toml         + aether-secondary.toml
 --masque -> aether-masque.toml  + aether-masque-lastconn.toml
 --mim    -> aether-masque.toml  + aether-masque-secondary.toml
```

Two engines sharing an identity file provision over each other's WARP device
registration and overwrite each other's remembered gateway — the cache this very
release already had to repair once. So the unit that can be raced is the IDENTITY
FAMILY, not the protocol: `WARP` (WireGuard, gool) and `MASQUE` (MASQUE, MASQUE×2).
Two lanes, two engine processes at most, and inside a lane the tactics run one
after another exactly as the ladder does. `SmartPlusPlanTest` asserts that
invariant for every DPI class rather than leaving it to a comment.

#### A race selects a strategy; it never becomes the session

Each lane brings up a throwaway engine on a port of its own
(`PortLease.leaseRacePorts`, based at 19819 so a relocated Private Space session
cannot collide with it), proves it carries outbound TCP with
`Diagnostics.runProxyStage` — the gate the chained stage already uses, which takes
a port parameter and leaves the four self-test circles alone — and is then killed,
winner included. The session the user ends up with is established afterwards by
`connectAttempt`, the same call Smart and a hand-picked protocol go through, on the
ordinary ports.

So the TUN, the LAN bridge, the self-test, the supervisor and the notification
behave identically in all three modes. The cost is one extra engine start for the
winner, and it is small by construction: the winning engine saved its gateway to
the engine's own `lastconn` file while proving itself, so the confirming connect
takes the cached path rather than scanning again.

If the race finds nothing, Smart Plus hands over to the Smart ladder instead of
failing. It can be slower than Smart in battery; it cannot be worse in outcome.

#### What it will not race, and why each one is a reason rather than caution

- **A pinned endpoint or range.** The user named the gateway; there is nothing to
  search for.
- **Zero Trust with the e-mail code.** That flow has the engine print a marker and
  wait on ITS OWN stdin. `LoginCodePrompt` can hold exactly one stdin, and asking
  the user for one code while two engines wait for it cannot be made correct.
- **A chained or Tor-fronted backend.** There the engine is stage one behind
  another carrier and the ports and lifecycle belong to the chain.

In all three the Smart ladder runs, the settings screen says so next to the
selector, and the diagnostics log names which condition applied.

#### Ordering rules

Evidence before inference. What connected here before goes first; then what the
network measurably looks like, reused from `SmartAuto.fingerprint` so the two modes
cannot drift apart in their reading of the same network; then general knowledge —
HTTP/2 before HTTP/3 on mobile data and on a UDP-throttled network, because
carriers have dropped QUIC for weeks at a time, and the two-hop protocols never
first because they pay for two handshakes and two scans. A tactic that failed here
inside the last six hours moves to the back of its lane rather than being dropped.

The second lane starts 7 s in, not at once and not at the reference
implementation's 45 s: the two lanes here cost the same, so a long stagger would be
the old ladder with extra steps, while a short one lets a cached gateway on the
first lane finish before a second engine has to start at all.

#### Supporting changes

- `IdentityVault.running` became a reference COUNT. With a boolean, the first lane
  to be reaped would seal — and sealing SHREDS the plaintext — pulling the WARP
  identity and the WireGuard private key out from under a lane that is still
  connecting.
- A racing engine publishes nothing to the UI: no `EngineMeta`, no `TorBootstrap`,
  no `BuildProvenance`, no `LoginCodePrompt`. Its log lines are tagged
  `engine/lane:<port>` so two interleaved engines can be told apart. A losing lane
  must not be able to write its endpoint into a row the user reads as "what I am
  connected through".
- Every lane kills its engine in `finally`, which also runs on cancellation, and
  `coroutineScope` does not return until all children have finished. This project
  has been bitten by exactly one stray engine before — a cancelled ladder left an
  unsupervised `libaether.so` running after Disconnect — and a race makes that
  failure mode multiply if it is not closed.
- `ConnectionProfile.bindOverride` carries a lane's port into `--bind`. Never
  persisted and not in `AetherController`'s wire format: a port borrowed for one
  race must not come back on the next connect.

Version unchanged: this ships as 1.3.1 / versionCode 15.

### Reverted — the "Repairing" connection state

1.3.1 briefly published a `ConnectionState.Repairing` so a chained session would
stop claiming **Connected** while its second stage carried nothing. The state was
truthful and the supervisor did know; publishing it still made things worse, and it
is gone.

`MainActivity` buckets every state into connected / busy / idle to decide what the
exit-IP pill shows, and the busy bucket CLEARS it. So each repair blanked the IP and
the flag — and the refill afterwards waits for the self-test's value and otherwise
fetches through `PortLease.socks`, the ENGINE's port rather than the chained
pipeline's front, so on a session whose second stage was still broken it never came
back. The field report was exactly that: the repair notice appeared, then "IP not
available" and a session that stayed dead.

Doing this properly needs the IP pill to survive a transient state and the fallback
fetch to use the pipeline's own port. Until that is built, the behaviour the user
had been running without this complaint is the better of the two. The reasoning is
kept as a comment in `ConnectionState.kt` so the next attempt starts from it.

What stays from that round is the QUIC latch below, which is unrelated to the state
and which the field log did not contradict.

### Fixed — a chained session said "Connected" while it carried nothing, and QUIC came and went (field report)

Report: *"Aether alone is perfect — speed is great, ping under 100 ms, no drops.
With Aether + Psiphon the first minutes are excellent, then the ping goes over
900–1000 ms, it cuts in and out, and sometimes it says connected and nothing moves
at all."* A six-minute diagnostics log of the `Aether → Psiphon` session came with
it.

**The cause is not in this app, and the log is unambiguous about that.** The Psiphon
tunnel dies and psiphon-tunnel-core re-establishes on another server:

```
15:04:59  PsiphonSocksFront udpgw session closed
15:04:59  Dial#1895: no active tunnels
15:04:59  this server refused the udpgw port forward (SOCKS reply 1)
15:04:59  this server does not allow outbound TCP/53
15:06:16  … the same again on the next rotation
15:07:20  session drops: udp/443 (QUIC) dropped=30, IPv6 flows refused locally=15
```

The Aether stage never reconnected once in those six minutes and its netstack
reported `tail-drops 0` throughout; the `flow N … nothing acknowledged for 3s`
resets cluster exactly on the rotations. One tunnel carried 1.9 MB and then died.
That is Psiphon's server pool, not the app.

Two things in the app made it feel worse than it was, and both are fixed.

**1. The app kept saying Connected.** `PsiphonTransport.onConnecting` only wrote a
line to the log — deliberately, because psiphon-tunnel-core emits it for its own
internal re-establish and clearing the connected flag would have made the
supervisor tear a healthy session down. The supervisor knew, the log knew, and the
user was looking at a green badge over a pipeline carrying nothing.

New `ConnectionState.Repairing`, published from the three places in the supervisor
that already knew: stage 2 not answering, a deliberate exit rotation settling, and
"up but carries nothing". Published on the EDGE only, so the card does not restart
its animation on every loop. It counts as busy, so the button still offers
Disconnect, and the connected timer keeps running because the session did not end.
Nothing about when the supervisor rebuilds changed.

**2. QUIC appeared and disappeared with every rotation.** This one is a real defect,
and this project had already written down why: `docs/PSIPHON_MEDIA_STALL.md` §4
suppressed UDP/443 from the first datagram and gave the reason — *"Consistency is
the point: intermittently working QUIC is far worse than QUIC that never works,
because Chromium caches 'HTTP/3 works for this origin'."* Later `CARRY_QUIC` turned
QUIC back on, correctly, because a server that intercepts udpgw carries it well —
and `onServerRotated` cleared the refusal so each new server got a fresh chance.
Together those two produce precisely the state §4 warned about.

`quicAllowed()` now latches: once ANY server in a session has refused the udpgw port
forward, QUIC stays off for the rest of that session. Apps fall back to HTTP/2 over
TCP and stay there. DNS is untouched — it has its own chain (TCP/53, then
DNS-over-HTTPS on 443) and every rotation still gets a fresh attempt at real UDP for
it, which is what `udpgwRefused` alone controls.

**Not changed, and why:** nothing tries to signal the failure back to the device.
SOCKS5 `UDP ASSOCIATE` has no per-datagram error, and injecting an ICMP
port-unreachable into the TUN belongs to hev-socks5-tunnel, which is fetched at
build time and is not in this tree — so any claim about its behaviour would be a
guess. The latch above makes the drop CONSISTENT, which is what browsers and apps
need in order to stop trying.

This applies to every chained backend, not only `Aether → Psiphon`: they all go
through the same front and the same supervisor.

### Fixed — live audio killed a chained session (field report)

Report: on `Aether -> Psiphon`, browsing, YouTube and downloads are fine and the
ping is good — but the moment the microphone opens for Gemini live dubbing or a
ChatGPT voice chat, the ping goes over 1000 ms and the session carries nothing.
Close the microphone and it slowly recovers.

The two numbers in the session summary settle what it was:

```
session drops: udp/443 (QUIC) dropped=272, congested udpgw frames dropped=0
```

The bulk lane never overflowed, so this was never congestion or head-of-line
blocking. Those 272 datagrams were discarded BY POLICY: both Psiphon servers in
that session had refused the udpgw port forward, and real-time audio rides UDP.

A silently discarded datagram tells the application nothing, so it retried for as
long as the microphone was open — and the retry storm is the rest of that log: a
flow holding 48 KB with nothing acknowledged, stalling twice inside a minute, and
`PsiphonHealth` rotating off a server for "refusing 6 different destinations" that
was in truth refusing our own retries. Closing the microphone stopped the storm,
which is exactly why it recovered.

**The fix: end the flow instead of swallowing it.** SOCKS5 has no per-datagram
error, but it has an end-of-association, and `hev.yaml` asks hev for `udp: 'udp'`
with a per-session timeout — so hev opens one association per UDP flow. When this
server has refused udpgw and a non-DNS datagram arrives, that association is now
closed. hev tears down exactly that one flow, the app's UDP socket fails, and both
Gemini Live and voice chat fall back to their own TCP path in about a second
instead of fighting for minutes and taking the tunnel down with them.

**The guard that makes it safe without hev's source.** hev is fetched at build time
and is not in this tree, so "one association per flow" is read off the config
rather than proven. An association that has carried even one DNS query is therefore
never closed. If the reading is right, audio flows end and DNS — which has its own
associations — is untouched; if hev instead multiplexes everything onto one
association, that association sees a DNS query almost immediately and becomes
permanently immune, leaving today's behaviour exactly as it is. The worst case of
this change is no improvement, never a broken resolver — which matters, because
once udpgw is refused this front answers DNS itself (DNS-over-TCP, then
DNS-over-HTTPS on 443) and those queries arrive through an association like
everything else.

An earlier attempt at this refused the UDP ASSOCIATE outright when udpgw was
refused. That was withdrawn before it was ever built: it would have cut the
device's DNS off entirely, for exactly the reason above.

**What this does not do.** It does not bring real UDP back. A Psiphon server that
refuses the udpgw port forward cannot carry UDP and no app-side code changes that;
audio then runs over TCP, with slightly more latency than UDP and unlike today it
runs at all. Applies to every chained backend — `Aether -> Psiphon`,
`Tor -> Psiphon` and the Tor paths all go through this front.

### Verification status

Unlike the source-level-only changes some earlier entries in this file describe,
this release was BUILT and checked:

| Check | Result |
| --- | --- |
| `gradle :app:compileDebugKotlin` | BUILD SUCCESSFUL, no errors |
| `gradle :app:testDebugUnitTest` | 128 tests, 0 failures, 0 errors, 0 skipped (74 → 88 `AccessToken`, → 100 label + endpoint parsers, → 128 Smart Plus planner + memory) |
| re-run after the QUIC latch, the Repairing-state revert and the UDP-flow fix | same: 128 tests, 0 lint errors, 40 warnings, four APKs |
| `gradle :app:lintDebug` | 0 errors, 40 warnings (was 1 error / 42 warnings) |
| `gradle :app:assembleDebug` | BUILD SUCCESSFUL, four APKs |

Every one of those four was re-run after the Private Space port work landed, after
the four field-report fixes, and again after Smart Plus, with the same result. Two
lint findings came out of this work and were fixed rather than suppressed: a
`NewApi` ERROR on `SubscriptionManager.getActiveDataSubscriptionId` in
`NetworkIdentity` (API 30 against minSdk 26 — now guarded exactly as
`SmartAuto.forActiveDataSim` guards it), and no warning remains on any file this
release touched apart from the pre-existing `ObsoleteSdkInt` in
`AetherVpnService.stopForegroundCompat` — same code as in the uploaded 1.3.0 tree,
only its line number moved.

Toolchain: OpenJDK 17.0.20, Gradle 8.9, AGP 8.7.2, Kotlin 2.0.21, compileSdk 35,
build-tools 35.0.0.

The four APKs are `armeabi-v7a` (versionCode 15001), `arm64-v8a` (15002),
`universal` (15003) and `x86_64` (15004) — monotonic, all above 1.3.0's
14001–14003, all `versionName 1.3.1`. The x86_64 one existing at all is the proof
that #8 is done.

Also confirmed against the packaged APK rather than against the source: the widget
metadata is `targetCellWidth/Height=1`, `minWidth/minHeight=40dp`,
`resizeMode=horizontal|vertical`, `maxResize 250x110dp`; `notif_action_disconnect`
is present in both locales ("Disconnect" / "قطع اتصال"); the v27 theme split is in
place. For the last round: the two new endpoint patterns and all four pipeline
labels are in the compiled dex, and the four APKs still report `versionName 1.3.1`
with versionCodes 15001–15004 — the version did not move. Zero lint findings
remain on any file this release touched, apart from the inherited one named above.

**What was NOT verified, and it matters.** No APK here can tunnel, and none has
been run on a phone. `app/src/main/jniLibs/` is absent from the tree these builds
used, so `libaether.so`, `libhev-socks5-tunnel.so`, `libaethertun.so` and the
pluggable transports are missing from every APK above — they are produced by
`scripts/build-natives.sh` and need the NDK, the Rust toolchain and the vendored
quiche checkout. The only native libraries packaged are the ones that arrive
inside AARs (Psiphon's `libgojni.so` and two AndroidX ones). So:

- the x86_64 split is proven to BUILD; whether the Rust engine cross-compiles
  cleanly for `x86_64-linux-android` is untested,
- the startup fix changes threading in `Application.onCreate`, the widget fix
  changes what a broadcast receiver reads in a cold process, and the Private Space
  fix changes which ports the engine is told to bind. All three compile and pass
  lint; none has been observed behaving.
- the Private Space fix in particular is the one claim here that CANNOT be checked
  without two Android profiles and a working engine. Two instances connecting side
  by side is untested.
- the Zero Trust e-mail flow depends on the engine printing the marker line and
  accepting the code on stdin. The app's half compiles and passes lint; that
  handshake has not been observed happening, and could not be here - an APK with no
  engine cannot perform it.

Before release, build with natives and confirm on a handset: the app opens
promptly with a large existing `diagnostics.log`, and the widget returns to
"Disconnected" after a disconnect, after a reboot, and after the launcher
restarts. From the last round, three more that only a phone can answer: the
connection row reads `Aether(WIREGUARD) → Psiphon` on all six modes, the endpoint
row fills in on a reconnect that reuses a cached gateway, and no Persian row is
left misaligned.

And Smart Plus, which is the claim in this release that a sandbox can say least
about. The planner's rules are covered by 18 unit tests and the lane mechanics
compile and pass lint, but a race only means anything on a real network: two
engines coming up side by side, the loser being killed cleanly, the winner's
gateway still being cached when the confirming connect runs, and the per-network
memory making the second connect on the same Wi-Fi quick. None of that has been
observed.

### Not in this release

Stated plainly, because these were reported and are NOT fixed here:

- **MASQUE / MASQUE×2 / WireGuard not connecting (#47, #46, #45, #43, #39, #38,
  #42).** Not an app bug. Every log ends at
  `api.cloudflareclient.com/v0a4471/reg` failing, with the camouflaged route timing
  out after it — WARP/MASQUE account REGISTRATION is blocked on those carriers, and
  the app never gets as far as a tunnel. Fixing it means not depending on that API,
  which is a transport decision rather than a patch.
- **LAN sharing: a configurable port and an option to switch the proxy credential
  off (#28, and the TV case in #35 / #36).** Deliberately NOT done. Exposing a
  loopback proxy to the LAN without a credential is precisely audit finding F-5 of
  1.2.9, the accept path (`LanGuard`) has tests that exist because of that hole, and
  the change could not be exercised on a device here. An untested change to that
  path is worse than the missing feature. The shape it should take: `sharePort: Int`
  (0 = 10810) and `shareRequireAuth: Boolean = true` threaded into `bindWithRetry`,
  with `LanGuard`'s local-address check staying MANDATORY even when the credential is
  off, and an explicit warning in the UI.
- Chinese (#25) was dropped from the list at the maintainer's request.

## 1.3.0 — Tor in four modes, engine core 2.0.0

App 1.3.0 / versionCode 14, signed with the same certificate, so it installs over
1.2.9 without uninstalling.

**Engine (core 1.9.0 -> 2.0.0)**

- All ten app engine patches rebased onto the new upstream sources
  (`Cargo.toml`, `lib.rs`, `netstack.rs`, `quic.rs`, `upstream.rs`, `wireguard.rs`,
  `masque.rs`, `masque_h2.rs`, `socks.rs`, `build.rs`); 21 merge conflicts resolved
  by hand rather than by preferring one side.
- `netstack.rs` was NOT merged textually. Thirteen upstream behavioural changes
  were ported into the app's own file and marked `AETHER-CORE-PORT 2.0.0`: a real
  connect deadline with env override, keepalive/dead-peer tuning, `ORPHAN_LINGER`
  plus `orphaned_at`/`aborted` state, `CloseWait` treated as connected, `SetAddrs`
  merging one address family without wiping the other, and a UDP socket leak when
  the app-side channel closes. The app's own data-plane work from 1.2.8 (CUBIC,
  bounded uplink buffer, BDP receive window, split datagram sizing, pinned smoltcp
  0.12) was kept in full.
- Three upstream core tests were adapted into the app's suite.
- `.upstream-baseline` refreshed to 2.0.0 for all ten files;
  `scripts/sync-core.sh` baseline moved 1.8.0 -> 2.0.0.
- New protocol exposed: `MIM` (`--mim`), MASQUE inside MASQUE.

**Tor (new)**

- `TransportBackend` grew from two modes to six: `TOR`, `AETHER_TOR`,
  `TOR_PSIPHON` and `TOR_AETHER` alongside `AETHER` and `AETHER_PSIPHON`, with a
  `TorMode` (`CHAIN` / `ONLY` / `REVERSE`) derived from the backend rather than
  stored separately.
- `TOR_AETHER` is `--tor-reverse`: Tor outermost, the tunnel inside it, WARP exit,
  and a local network that cannot see that a tunnel exists. It needs no Tor front
  (the device's traffic travels inside WARP, which carries UDP natively), so
  `needsTorFront` excludes it explicitly - treating it like a Tor exit would have
  broken DNS in the one Tor mode where DNS was never the problem.
- New `ConnectionProfile.effectiveProtocol`: the reverse chain is forced to MASQUE
  and `AETHER_MASQUE_HTTP2` is forced with it, because core 2.0.0 refuses `--wg`
  and `--gool` there (Tor is TCP-only; WARP's WireGuard endpoints are UDP-only).
  Sending the user's WireGuard selection anyway would kill the engine at startup -
  which from the app's side is indistinguishable from a blocked network. The UI
  disables the protocol selector in that mode and states the reason.
- The reverse chain gets a single connect candidate on MASQUE/h2 with the Tor
  bootstrap budget instead of the Smart Auto ladder: rungs that try `--wg` or
  `--gool` would fail instantly and burn the time Tor needs to bootstrap.
- New `transport/TorSocksWire.kt`: the SOCKS5 UDP and DNS-over-TCP framing lifted
  out of `TorSocksFront`'s receive loop into a pure, total object, covered by
  `TorSocksWireTest` (17 cases). Every bug this file can contain looks like
  something else from the outside - a port read one byte off answers a QUIC attempt
  with a DNS reply, or drops every name lookup on the device, and both present as
  "connected, nothing loads", which is exactly how the 1.2.7 Tor backend failed.
  Two hardening fixes came with the move: the declared datagram length is now
  checked against the buffer size, and the IPv4/IPv6 address bounds are checked
  against the declared length rather than the array.
- `TorSocksFront` counts malformed datagrams and reports them in `dropSummary()`.
  A flood of unparseable packets was previously indistinguishable from silence.
- Three engine Tor settings the app could not reach are now in the UI, under
  **Settings -> Tor**: bridge country (`AETHER_TOR_COUNTRY`), bootstrap patience
  (`AETHER_TOR_DIRECT_SECS`) and the reachability check (`AETHER_TOR_CHECK`). All
  three are sent only when Tor actually runs and only when the user deviates from
  the engine's default, so the engine keeps owning its own defaults. The first two
  are hidden in `Aether -> Tor`, where bridgedb is never consulted.
- `AETHER_TOR_LOG` follows the existing core log level: `debug` when the app is set
  to DEBUG, otherwise left to the engine. The engine's Tor log understands only
  info/debug/trace, so the app's quieter levels have no counterpart to send.
- SECURITY AUDIT 1.3.0, score 88/100 - full report in
  `docs/SECURITY_AUDIT_1.3.0.md`, summary table in both READMEs and in the release
  notes, EN + FA. Nine areas (the seven of the brief plus on-device exposure and
  supply chain). App signing and update compatibility are OUT OF SCOPE by product
  decision - 1.3.0 keeps the 1.2.9 signing identity so it installs over an existing
  install - which is why 88 is not comparable with the 79 of the 1.2.9 report.
  Findings, in severity order: the kill-switch lockdown TUN routes `::/0` only when
  `ipv6LeakProtection` is on (`AetherVpnService.ensureLockdownTun`), `killSwitch`
  defaults to false, no `FLAG_SECURE` anywhere, clipboard copies of the API key and
  the LAN password are not marked `EXTRA_IS_SENSITIVE`, two of three `NetProbe` geo
  providers are cleartext on a raw socket, R8 is off, CI has no dependency or secret
  scanning and pins actions by tag, and the Psiphon AAR ships as a committed binary
  with a recorded hash but no upstream provenance. Verified clean: no hardcoded
  secret, AES-256-GCM only under a non-exportable keystore key, no custom
  `TrustManager` / permissive verifier anywhere and system-only trust anchors (no
  MitM path), hostnames never resolved locally, DNS over TCP inside Tor, non-DNS UDP
  dropped, `::/0` unconditional in chained modes, log and engine identity encrypted
  at rest with fail-closed behaviour, five permissions, immutable `PendingIntent`s,
  no WebView, three `Log` calls in the whole app.
- SECURITY REMEDIATION, same build, seven of the ten findings fixed after the audit:
  score 93/100 recomputed on the same nine weighted areas (88 as audited stays in
  the report - the findings section deliberately still describes the code that was
  reviewed, and the new section 6 records what changed).
  * F-1: `ensureLockdownTun` claims the v6 address and routes `::/0` UNCONDITIONALLY.
    A blackhole has no connectivity to break, so gating it on `ipv6LeakProtection`
    only ever left a v6 path open in the window the kill switch exists for.
  * F-2: `killSwitch` defaults to TRUE, in `ConnectionProfile` and in the
    `ProfileStore` fallback (both were needed - the store's `?: false` would have
    kept the old behaviour for every fresh install). A user's explicit "off" is
    written to disk and survives; the new default only applies where the key was
    never written.
  * F-3: new `ui/components/PrivacyGuard.kt`. `SecureSurface()` holds `FLAG_SECURE`
    while a secret-bearing surface is composed, REF-COUNTED so overlapping surfaces
    cannot strip protection from a screen that is still showing. Wired into the LAN
    credential rows (`SharePanel`), the open log console (`DiagnosticsPanel`), the
    Gemini key page (`AiPages`), the Access token block (`SettingsScreen`), and set
    directly on the window in `CrashReportActivity`.
  * F-4: `copySensitive()` sets `ClipDescription.EXTRA_IS_SENSITIVE` (the literal key
    below API 33) for the proxy credential, the verbatim diagnostics log and the
    crash dump. The redacted log export and the `127.0.0.1:<port>` rows stay plain
    copies on purpose. Correction to the audit text: there is no copy button for the
    API key at all - `AiPages` only PASTES from the clipboard.
  * F-5: `NetProbe.GEO_PROVIDERS` is TLS-only. `ip-api.com:80` is gone as an IP
    source and `1.1.1.1` moved to 443. The IP literal is kept deliberately so no DNS
    is in the probe path; the certificate carries `1.1.1.1` as an iPAddress SAN and
    `tlsWrap` verifies it.
  * F-5 FOLLOW-UP (version unchanged: 1.3.0 / versionCode 14): the sentence that used
    to end the item above - "country refinement is still ip-api over HTTP and is still
    informational only" - was describing a leak, not a caveat. `refineCountry()` ran
    on EVERY successful probe, including the one on the DIRECT path
    (`fetchIpInfoDirect`, the disconnected IP badge). That opened a second, plaintext
    HTTP connection to `ip-api.com:80` on a RAW socket - so
    `cleartextTrafficPermitted="false"` never applied to it - whose request line
    carried this device's real public IP, over the operator's own network, on every
    IP refresh while the user had no tunnel. The address itself is not the secret
    (the operator sees it as the source of every packet); what leaked was a
    plaintext, app-shaped `GET /json/<own ip>?fields=status,countryCode` that
    identifies THIS APP on a network where running it is the sensitive fact.
    Two conditions now gate that request, both in one pure function
    (`NetProbe.shouldRefineCountry`, pinned by `NetProbeGeoPolicyTest`, 7 cases):
    it must go THROUGH THE TUNNEL, and it only happens when the provider returned
    no country at all. Harmonising two geo databases so the flag looks consistent
    is not a reason to emit a request. `fetchIpInfoDirect` no longer references
    `refineCountry` at all - verified in the bytecode, not just the source.
  * Same follow-up, the header that made it worse: the probe sent
    `User-Agent: Aether/1.0`, i.e. the app's own name, in that plaintext request.
    Now `Mozilla/5.0`. The two other app-naming agents are gone with it -
    `aether-ping` in `PingMonitor` and `Aether-Android/1.2.9` in `GeminiHttp` (which
    also still claimed 1.2.9 inside a 1.3.0 build). Both of those ride inside TLS, so
    they were never operator-visible; they named the app to the endpoint for nothing.
  * Reviewed and deliberately NOT changed: the watchdog's liveness probe in
    `AetherVpnService.probeTunnelOnce` completes a TLS handshake without verifying
    the hostname. It sends no request and trusts nothing from the answer - it only
    measures whether bytes make a round trip through the payload path - and adding
    verification would turn a captive portal or an interception proxy into "tunnel
    wedged" and drive a reconnect loop. A comment now says so on the spot, so the
    next reader does not "fix" it into a regression.
  * F-7: non-blocking `cargo audit` step in CI (placed after the engine build, since
    the Rust tree does not exist before `fetch-natives.sh`) plus new
    `.github/dependabot.yml`: **one** entry (gradle), monthly, all bumps grouped into
    a single PR. Cargo and github-actions were dropped from it on purpose — the
    engine tree is re-synced from upstream by `sync-core.sh` on every run, so a PR
    against it cannot survive, and action versions are reviewed by hand together
    with the still-open F-9. No Gradle CVE scanner: the OWASP plugin now needs an
    NVD API key, and downloading a scanner binary without a verifiable checksum
    would be its own supply-chain hole.
  * CI hygiene, from the first push of this config: the Build APK workflow now has a
    `concurrency` group, so a newer push to a branch cancels the older run instead
    of letting two builds sign, push back and publish to the same tag (a tag build
    is never cancelled), and the job is skipped for dependency commits — merging a
    Dependabot PR no longer publishes a release. Build one deliberately with
    Actions → Build APK → "Run workflow".
  * F-8: new `app/libs/PROVENANCE.md` - size, SHA-256, upstream project, and an
    explicit statement of what that hash does NOT prove.
  * STILL OPEN, deliberately: F-6 (R8 off - a reflection break in Compose or the
    Psiphon AAR shows up on a device, not in a unit test) and F-9 (actions still
    pinned by tag; the commit SHAs could not be looked up without network access and
    are not something to invent).
  * All of this is SOURCE-LEVEL. It compiles, 67 unit tests pass, `versionCode`/
    `versionName` unchanged - but no one has yet confirmed on a phone that the
    recents thumbnail is blank, that the clipboard preview hides the LAN password on
    Android 13+, or that `1.1.1.1:443` verifies on every OEM TLS stack.
- DOCS: the 1.3.0 section of `README.md`, `README.fa.md` and
  `.github/release-notes.md` now lists NEW FEATURES ONLY. The bug-fix and
  device-test sections were removed from all three - that record lives here, in this
  file, which is where an engineering log belongs. Nothing else in the version
  history was touched.
- DOCS RTL, second attempt (the first did not hold): the Persian 1.3.0 sections in
  `README.fa.md` and `.github/release-notes.md` are now EXPLICIT per-element HTML -
  `<h2 dir="rtl" align="right">`, `<p dir="rtl" align="right">`, `<ul>`, `<table>`
  with `align="right"` on every cell. The wrapper-plus-RLM approach of the previous
  build was overridden by the `dir="auto"` GitHub emits on every block it generates
  from markdown; an element carrying its own `dir`/`align` cannot be overridden.
  Cost of the method: markdown syntax is not processed inside those blocks, so the
  emphasis, code spans and links there are real HTML tags. Verified by rendering the
  file through pandoc + WeasyPrint and looking at the result.
- DOCS: `SOURCE_MANIFEST.sha256` regenerated. It still carried the 1.2.9 hashes and
  had never been refreshed for 1.3.0; 76 of its 4,189 entries were stale, including
  the whole engine 2.0.0 sync and every app file this release touched.
- TOR CONNECT FIX (second device test, root cause in the field log): every
  tor-fronted stage 1 (`Tor`, `Tor -> Psiphon`, `Tor -> Aether`) failed 4.4 s into
  the connect on a network that was NOT blocking Tor. The engine binds its SOCKS5
  listener at launch, so `PortProbe.awaitOpen` returned after 300 ms without
  spending any of the 300 s bootstrap budget computed for the attempt, and
  `Diagnostics.runProxyStage` then ran ONE `NetProbe.checkSocksHandshake` with a
  fixed 4000 ms timeout. The log shows the verdict "does not speak SOCKS5" at
  +4013 ms while the same log has Tor at 30 %, with all 7 directory authority
  certificates fetched. Four changes:
  - New `core/TorBootstrap.kt` parses the engine's own
    `tor reaching the network: NN%` line into a `StateFlow` with a monotonic,
    injectable clock; any percentage CHANGE counts as movement, including downwards,
    because a bridge retry restarts the bootstrap from a low percentage.
    `AetherProcess` resets it per engine start and feeds it from the log drain.
  - `Diagnostics.runProxyStage` gained `handshakeGraceMs`, `alive` and `abort`, and
    `awaitSocksHandshake` retries every 1.5 s until the deadline instead of failing
    on the first attempt. `handshakeGraceMs = 0` keeps the old single-shot gate for
    every non-Tor stage, where an open port really does mean ready.
  - `AetherVpnService.connectAttempt` passes the REMAINING attempt budget as the
    handshake grace for `TorMode.ONLY` / `REVERSE`, and aborts early when
    `TorBootstrap.stalled()` reports no movement for 45 s (bridges off) or 420 s
    (bridges permitted). `torBudget()` raises the per-attempt budget to 600 s when
    bridges are permitted, because the engine's own bridge fallback starts at
    `AETHER_TOR_BRIDGE_SECS` = 360 s and the previous 300 s cut-off made it
    unreachable in every case.
  - `err_selftest` is no longer the message for a Tor that never reached the
    network: `err_tor_blocked` (with the percentage it stopped at),
    `err_tor_no_progress` and `err_tor_no_stream` name the actual cause, and
    `state_tor_bootstrap_pct` / `state_tor_bridges` show progress in the
    notification. EN + FA.
  - `TorBootstrapTest` (15 tests) covers the parser against verbatim field-log
    lines, the downward-movement rule, the stall detector and the silent-engine case.
- DOCS FIX (device test): `README.fa.md` closed its `<div dir="rtl">` after the
  1.2.5 section and carried an orphan `</div>` 380 lines later, so most of the
  Persian README rendered left-aligned. The wrapper now spans the whole Persian
  body; every RTL wrapper also carries `align="right"` and every block inside one
  starts with U+200F, so the direction holds whether GitHub honours the wrapper's
  `dir` or applies `dir="auto"` per block. The Persian release notes gained the
  "fixed in this build" section that existed only in English.
- CRASH FIX (device test): `AetherVpnService.onStartCommand` now calls
  `startForeground` for EVERY action, before anything else. Only the connect branch
  did, while `AetherController.disconnect()`, the notification action, the tile and
  the widget all arrive through `startForegroundService()` - which Android answers
  with `ForegroundServiceDidNotStartInTimeException` ten seconds later. Every
  disconnect was a guaranteed process kill, and the field log shows it happening
  with the tunnel still up. The strict-kill-switch path needed it doubly: it keeps
  the service alive on purpose.
- The crash handler in `AetherApp` now treats that one framework exception as
  survivable - it posts the missing notification through
  `AetherVpnService.rescueForeground()` and keeps the session - and, for every
  other fatal exception, stops the engine child process on the way out so a dying
  app cannot leave `libaether.so` running.
- CANCELLATION FIX: the self-test and the chained stage gate used `runCatching`,
  which swallows `CancellationException` too, so a disconnect during verification
  was reported as a failed self-test. The ladder then started the NEXT engine on an
  already-cancelled job, and nothing was left to supervise or stop it - the log of
  such a session ends mid-attempt with a live engine still bootstrapping Tor. The
  probes now use `probeOrFalse`, which re-throws cancellation, and `runLadder`
  calls `ensureActive()` before each rung.
- `SegmentedSelector` wraps into an even grid above three options instead of
  squeezing every option into one row. `MASQUE×2` made the protocol row five wide,
  which is narrower than the word "WireGuard": the label broke across two lines
  while its neighbours did not. Five options are now 3 + 2, the short row is padded
  with a weighted spacer so cell widths stay uniform, and labels are single-line.
- `ui/components/LtrOutlinedTextField` gained an `isError` parameter. The Tor
  reachability field needs it: the engine parses `host:port` with a fallback to 443
  on a bad port, so a typo silently becomes a different target - in the one setting
  whose job is telling a working Tor from a broken one.
- `.github/removed-sources.txt` no longer lists `transport/TorSocksFront.kt`, which
  1.3.0 legitimately brings back, and `scripts/purge-stale-sources.sh` now refuses
  to delete a listed Kotlin file that the current sources still reference. Without
  both, CI deleted the new file, pushed the deletion to the branch and failed the
  compile with `Unresolved reference 'TorSocksFront'`.
- New `transport/TorCountries.kt` rather than reusing `ExitRegions`: a bridge
  country is where the user IS, an exit region is where the user wants to come out,
  and Psiphon's egress list contains none of IR, CN, TM or BY.
  `fromStoredName` maps the names retired in 1.2.7 so an old profile cannot
  silently resolve to a different provider.
- New `transport/TorSocksFront.kt`: a local SOCKS5 front on port 1821 that answers
  `UDP ASSOCIATE` itself, resolves DNS over TCP inside Tor with RFC 1035
  length-prefix framing, relays `CONNECT` with the hostname unresolved (so `.onion`
  works and no name is looked up locally) and drops other UDP with counters.
  Without it a Tor session establishes and carries nothing — the 1.2.7 failure.
- New ports in `TunnelConfig`: `TOR_SOCKS_PORT` 1820 (the engine's Tor listener
  with `--tor`) and `TOR_FRONT_PORT` 1821.
- `AetherVpnService.connectTor()` drives both Tor-exit modes, with a five-minute
  bootstrap budget instead of the endpoint-scan budget, and the front's lifecycle
  tied into both teardown paths ahead of the engine.
- Chained stage 1 no longer forces `TransportBackend.AETHER`, which would have
  stripped `--tor` from the engine's argv and produced a session with no Tor in it.
- `--tor-only` skips the Smart Auto ladder: with no tunnel there is no endpoint to
  scan and every rung would be the same invocation.
- Profile: `torBridges` (`AUTO`/`ALWAYS`/`OFF`) and `torBridgeLines`, validated
  against a transport allow-list and capped at 12 lines, persisted in
  `ProfileStore`.
- `AetherProcess` exports `AETHER_TOR_DIR` (persistent directory cache) and
  `AETHER_TOR_PT`/`AETHER_TOR_PT_DIR` pointing at `libpt-lyrebird.so` in
  `nativeLibraryDir`, the only place on Android a spawned binary may live.
- Psiphon's upstream port now follows the backend, so `Tor -> Psiphon` cannot dial
  out through the wrong hop.

**UI / AI**

- A Tor settings group appears only in the modes that contain Tor; the bridge rows
  are disabled with a reason in `Aether -> Tor`, where bridges do nothing.
- The exit-country row explains that Tor picks its own exit per circuit.
- Three AI topics (`BACKEND` rewritten, `TOR_BRIDGES`, `TOR_BRIDGE_LINES`); the AI
  prompt snapshot now includes read-only context (backend, proxy mode, split mode,
  upstream proxy, Tor settings), so advice can no longer ignore the actual pipeline.
  Tor settings are readable by the assistant and not writable.
- 18 new strings in `values/strings.xml` and `values-fa/strings.xml` (both files at
  the same count).

**Build**

- `scripts/build-natives.sh` builds the engine with `--features tor` (core 2.0.0
  keeps Tor behind an opt-in feature; without it the Tor arguments do not exist)
  and then greps the stripped binary for `--tor-bind` to prove the feature took.
- New `pt` target builds lyrebird as a PIE named `libpt-lyrebird.so` per ABI; a
  missing Go toolchain is a warning, not an error.
- CI gained a lyrebird step with `continue-on-error: true` — bridges must not be
  able to hold back a release in which everything else works.

## 1.2.9-r3 — security remediation + the connected mark

Version unchanged (1.2.9 / versionCode 13) and signed with the same certificate,
so this installs over an existing 1.2.9 without uninstalling.

**Security (full report: `docs/SECURITY_AUDIT_1.2.9-r3.md`, score 79 -> 93)**

- The on-disk diagnostics log is now encrypted with a hardware-backed AES-256-GCM
  key; a plaintext log from an older build is read once, then shredded.
- The engine's identity files (WireGuard private key + WARP device) are sealed at
  rest and only exist in the clear while the tunnel is actually running.
- LAN sharing is no longer an open proxy: remote clients must come from a local
  address AND authenticate (SOCKS5 user/pass or HTTP Basic) with a generated
  16-character password shown in the Share card. Loopback is unchanged.
- TLS trust anchors are restricted to system CAs, so a user-installed root
  certificate can no longer intercept the app's own requests.
- The app verifies its own signing certificate against the published fingerprint
  and shows it, with the APK's SHA-256, in About; CI publishes both per artifact.
- New "Copy logs, addresses removed" button for logs that get pasted in public.
- CI runs the unit tests before building a release (16 new tests).

**UI**

- Connected no longer shows a generic tick: it draws Aether's own A - the launcher
  icon's letterform - lit, colour-cycling, with a diagonal light sweep, an internal
  scan bar over a hairline grid, and a one-shot reveal wipe when the tunnel comes
  up. Composed only while connected.

# 1.2.9-r2 — crash on connect fixed + security audit (version unchanged)

Version stays `1.2.9` / code 13; `PATCHLEVEL` unchanged. No dependency, permission,
manifest or native change.

## The crash (`IndexOutOfBoundsException: No group 1`)

- **Root cause: `AiRedaction.redactLine` asked for a capture group the IPv6 pattern
  does not have.** `IPV6` is written entirely with non-capturing `(?:…)` groups, so
  `m.groupValues[1]` threw on the first log line containing any IPv6 literal. `IPV4`
  on the next line happens to have one capture group, which is why the same idiom
  worked there and hid the asymmetry. The engine prints
  `[+] identity ready: … ipv6=2606:…` on every connect, so the redactor threw on
  every session, in both plain and chained mode.
- **Why the app closed a minute or two after connecting:** `MainActivity` runs the
  on-connect analysis at the end of the *connected* branch, after the tunnel
  self-test and after an exit-IP probe that waits up to 100 s. The digest is built
  inside `scope.launch`, and `SupervisorJob` does not catch an unhandled throw — it
  reached the thread's default handler and killed the process, leaving the
  `last_crash.txt` the crash screen showed on the next launch.
- **Fix:** both address rules mask `m.value` (group 0, which always exists), so the
  code no longer depends on how a pattern is bracketed. Labelled rules read their
  label with `groupValues.getOrElse(1)`.
- **Containment, in three layers:** `digest` redacts through `safeRedactLine`, which
  drops a line that throws (never falls back to its raw text); `analyze` builds the
  digest inside `runCatching` and reports an ordinary failure; and `AiSession.scope`
  now carries a `CoroutineExceptionHandler`, so no AI failure can ever take the
  tunnel down again — it is logged, the `Running` state is resolved so the UI cannot
  hang on a spinner, and the app keeps running.

## Two leaks found while fixing it

- **IPv4-mapped IPv6 was half-masked.** The general rules matched `::ffff:203` and
  stopped, leaving `.0.113.9` in the digest — three octets of the public address the
  rule exists to hide. Mapped forms (`::ffff:1.2.3.4`, `0:0:0:0:0:ffff:1.2.3.4`,
  `::1.2.3.4`) are now matched whole and masked with the IPv4 policy, private and
  loopback quads still kept intact.
- **JSON-shaped identifiers were not recognised.** Half of this app's log is
  Psiphon's JSON notices: `sessionId=` matched, `"sessionId":"5a4c…"` did not, so the
  Psiphon session id travelled to the model verbatim. Both the credential rule and
  the identifier rule now accept the quoted form.

## Tests

- `AiRedactionTest` grew from 8 to 12 cases: every IPv6 text form (full, compressed,
  `::`, `::1`, link-local, bracketed, IPv4-mapped), JSON-shaped identifiers and
  credentials, and a realistic mixed session log through `digest` asserting both
  halves — nothing identifying survives, and the timestamps, loopback address, Rust
  module paths and build stamp do.
- Every assertion in that file would have failed before this fix, which means the
  suite was never run against the shipped build. `gradle :app:testReleaseUnitTest`
  should be a CI step; see `docs/SECURITY_AUDIT_1.2.9.md` §7.

## Security audit

- Full mobile-app security audit of the shipped tree: `docs/SECURITY_AUDIT_1.2.9.md`.
  **Weighted score 79/100**, summarised in both READMEs, with the AI-privacy
  statement (what the digest contains, and what is never sent) stated explicitly.
  The committed release signing key (`.github/ci-keystore.jks.b64`) remains the one
  critical finding and still needs a key rotation, not a code change.

# 1.2.9 — AI path fixes (version unchanged)

Version stays `1.2.9` / code 13; `PATCHLEVEL` unchanged.

## Errors (a2, a4, a6)

- **a2 was misdiagnosed by the app.** `Internal error encountered` is Google's own
  HTTP 500. `GeminiClient` mapped every `>= 500` to `TRANSPORT` and told the user
  "could not reach Google through the tunnel" — about a request that reached Google
  and came back. That is why "try again" always worked. New `AiErrorKind.SERVER_ERROR`
  says whose fault it is, and 5xx/429/socket failures are now retried
  automatically (3 attempts, exponential, capped at 6s), so the user usually never
  sees it.
- **a4 (429) now honours Google's own `retryDelay`.** The field log has five 429s
  in ten seconds because nothing read `"Please retry in 2.379075806s"`, and there
  was no backoff at all. Both `Retry-After` and `error.details[].retryDelay` are
  parsed and obeyed.
- **a3/a6 root cause: thinking tokens, not a parse bug.** `maxOutputTokens` is a
  budget for *everything* the model emits, and on a thinking model the reasoning
  is spent first. The advisor asked for 1400 tokens with a 12,000-char log digest
  attached; the model spent the budget thinking and returned an empty or truncated
  body — logged as `200` + `advisor answer could not be parsed as JSON`. Fixed by
  `thinkingConfig.thinkingBudget = 0`, `responseMimeType: application/json`,
  budgets raised to 3072/5120, skipping `thought: true` parts, and detecting
  `finishReason: MAX_TOKENS` as its own error kind with one larger retry.
- **Every error string is translated.** `gate.name`, `"unreadable answer"` and
  `"empty log"` reached the screen as raw English. Causes now travel on the state
  (`AiMessage.errorKind/gate`, `AiProbe.Failed.gate`, `AiAdviceState.Failed.kind/reason`)
  and one function, `aiFailureText`, owns the wording. Google's English boilerplate
  is no longer appended when the translated headline already says it.

## Models

- New `AiModelPolicy`: a five-model allow-list, applied on **all four** paths —
  fresh discovery, the cached list replayed at startup, the default pick, and the
  id sent to `generateContent`. Filtering only on discovery is why a refresh (or a
  cache from 1.2.8) put image/video/Pro models back.
- Ordered newest→oldest by rank, not by string sort (lexically `3.1` precedes
  `3.5` and `flash-lite-latest` sorts after every numbered release).
- Picker rows are numbered `1. gemini-3.8-flash`, from the model's fixed position
  in the allow-list rather than its index in the visible list.
- Discovery succeeding with none of the five available now says so, instead of
  leaving an empty picker to refresh forever.

## UI and chat

- **AI icon coverage is now complete**: `AiTopic.RECONNECT_SECS` existed with
  nothing pointing at it, and three unrelated tuning fields shared one icon
  labelled "TLS groups". Split into one block per field. Icons added to the About
  row and the split-tunnel app picker. Zero topics are now unanchored.
- **"Applies on the next connection" is a dialog.** It was 12sp dimmed text inside
  the card, directly above Apply — so pressing Apply scrolled it out of view in
  the chat and it sat below the fold in the advisor. The single most important
  sentence in the feature was the least visible thing on screen.
- **Retry icon on failed sends**, resending the original prompt (kept on the
  bubble as `sourcePrompt`, not guessed by walking backwards — after an edit or a
  delete the bubble above a failure is not necessarily its cause).
- **Edit and delete messages**, single and in bulk: long-press to start a
  selection, select-all, confirmed bulk delete. Editing truncates the conversation
  after that point and re-asks.
- **"Did not understand? Ask the assistant"** on every explanation sheet. Carries
  the option name, its current value, the app's own description and the
  explanation the user just failed to follow into the chat, phrased in their
  language, and asks for a simpler answer.

## Security (audit of the AI path)

- **The advisor was sending the user's WARP identity to Google.** `AiRedaction`
  masked IPv4 and nothing else, so the `identity ready:` line the engine writes on
  every connect left with `device=<uuid>` and a full, globally routable, stable
  IPv6 intact. Masking v4 to a /16 while shipping a /128 is not partial protection.
  IPv6 is now masked to its /32, and `device=`/`sessionId`/bare UUIDs are removed.
  Verified against all 312 lines of the supplied field log.
- v1 (JAR) signing turned **off**: `minSdk` is 26, so it was unused on every
  supported device and is the scheme Janus-class attacks target. Certificate
  unchanged, so in-place updates still install.
- The committed public release key (`.github/ci-keystore.jks.b64`) is **no longer
  used automatically** — it now needs `-PaetherAllowPublicCiKey=true`. CI is
  unaffected. The file stays until the key is rotated; deleting it would break
  updates for every existing user. Still the project's highest-severity issue.
- `android:usesCleartextTraffic="false"` stated explicitly in the manifest.
- First unit tests in the project, covering the two pure-Kotlin pieces above.

## Install warning (a1)

Not fixed, and not fixable in the build. See `docs/PLAY_PROTECT.md`.
WhiteAestherMobile does not avoid this dialog — its own README documents it as
expected for a sideloaded APK, and its Play submission has the required in-app
VPN disclosure still open as a blocking item. The `a1` prompt is Play Protect's
unknown-APK scan, keyed on the app being absent from Google's corpus, not on how
it is signed.

## 1.2.8-r8

## 1.2.9 - Gemini AI features (version unchanged: 1.2.9 / versionCode 13)

Added, all optional and all inert until the user enters their own key:

- `data/GeminiStore.kt` - AI preferences in their own DataStore, so a settings
  reset cannot destroy an API key. The key itself lives in `SecretStore` under the
  new `GEMINI_KEY` alias, sealed with the existing hardware-backed AES-GCM key.
- `ai/GeminiHttp.kt` - HTTPS to Google dialled through the tunnel's own local
  SOCKS5 proxy, destination sent as `ATYP=0x03` (resolved at the exit), TLS
  terminated on-device with hostname verification enforced, chunked transfer
  decoding done on bytes rather than on text so a multi-byte Persian answer cannot
  be corrupted at a chunk boundary. No new dependency: `org.json` is the platform's.
- `ai/GeminiClient.kt` - model discovery (`GET /v1beta/models`, paginated and
  bounded) and `generateContent`, with every failure translated into one of six
  user-facing error kinds.
- `ai/AiGate.kt` - the availability rule: the AI needs a key, a connected tunnel
  and the chained `Aether -> Psiphon` mode, because the app excludes its own
  package from the VPN and Google's AI endpoints refuse WARP exit addresses.
- `ai/AiRedaction.kt` - the log digest that leaves the device: credentials removed,
  the user's own Gemini key removed, public IPv4 masked to /16, private and
  loopback addresses kept, hard character cap.
- `ai/AiPatch.kt` - the ALLOW-LIST that bounds what a model may change: 29 tuning
  keys, each value validated and snapped to the presets the UI itself offers. The
  network backend, upstream proxy, routing lists, manual endpoint, proxy/split/
  blocked-app policy, LAN sharing and every Zero Trust field are deliberately
  absent, with the reason recorded per item in the file.
- `ai/AiTopic.kt` - ~50 settings, each with a factual English description written
  from the engine's real behaviour, so an explanation is grounded rather than
  guessed. `ai/AiPrompts.kt` - the three system instructions and a tolerant parser
  for the JSON contract. `ai/AiSession.kt` - process-lifetime state (settings,
  models, conversation, advice), a bounded explanation cache, debounced key writes,
  bounded chat history and a 90 s floor between automatic analyses.
- `ui/ai/` - the AI icon and explanation sheet, the Gemini-style chat screen
  (asymmetric turns, light Markdown, typing indicator, `imePadding` composer), and
  the AI settings and advisor pages, all built from the existing settings row
  primitives.

Changed:

- `ui/settings/SettingsUi.kt` - every row primitive, `GroupCaption` and
  `SettingsBlock` gained an optional `aiTopic`. The icon is defined once, in
  `BaseRow`, so it cannot drift between row types; with the hints switched off it
  renders nothing and the row metrics are identical to 1.2.8's.
- `ui/settings/SettingsScreen.kt` - three new routes (`AI`, `AI_CHAT`,
  `AI_ADVISOR`), one `LocalAiHost` provider for the whole settings area, and a
  topic on every option in every page. `AI_CHAT` is a navigation ROOT, like
  `QUICK`, so backing out of the chat leaves settings.
- `ui/HomeScreen.kt` - an assistant group at the top of the menu and an AI button
  in the top-end corner, both OUTSIDE the `FitToHeight` subtree so the connect
  button is not scaled down on small phones.
- `MainActivity.kt` - the on-connect analysis runs at the end of the "connected"
  phase, after the self-test has written its results, so the model reads the whole
  connect rather than its first two seconds. `AetherApp.kt` attaches the store.
- `res/values/strings.xml` + `res/values-fa/strings.xml` - 84 new strings each;
  both files verified to hold identical key sets and identical format arguments.
- `README.md`, `README.fa.md`, `native/aether/README.md`,
  `native/aether/README.fa.md` and `.github/release-notes.md` document the feature
  in English and Persian. The 1.2.8 sections were removed from the 1.2.9 release
  notes as requested.

Deliberately NOT changed: `versionName` stays 1.2.9, `versionCode` stays 13,
`PATCHLEVEL` stays 1.2.9 (CI asserts it against the stamp inside the shipped
`libaether.so`, and no native source was touched), and the engine is untouched.

## 1.2.9

### Home screen shortcut
- `ui/HomeScreen.kt`: the top-end tune icon opens the new `SettingsRoute.QUICK`
  instead of `SettingsRoute.HOME`. The drawer's Settings row is unchanged.
- `ui/settings/SettingsScreen.kt`: `SettingsRoute.QUICK` added; it renders the
  same `SettingsHomePage` with `quick = true`, which emits the Tunnel group and
  the reset action only. `SettingsHost` treats QUICK as a stack ROOT, so back
  leaves settings instead of descending into the full tree.
- `res/values*/strings.xml`: `quick_settings_title` / `quick_settings_subtitle`
  in English and Persian.

### Engine core 1.8.0 -> 1.9.0 (rebased by hand)
- Taken from upstream verbatim: `cli.rs`, `masque.rs`, `masque_h2.rs`, the core's
  own docs. New: capsule batching, a dedicated H2 send task, 64 KB DATA frames,
  tier-based H2 flow-control windows.
- Hand-merged, app patches preserved: `lib.rs` (warp-in-warp manual hops,
  `masque_tunnel_mtu()`/`H2_TUNNEL_MTU`, `select_wg_peers(.., avoid)`,
  `select_scan_mode_str(tip)`, upstream's 23 new unit tests) and `sysprofile.rs`
  (upstream's `buffer_override()` + H2 windows adopted; the r4/r6 buffer figures
  kept).
- Left at the app's version on purpose: `netstack.rs`, `wireguard.rs`,
  `wg_prober.rs`, `prober.rs`, `quic.rs`, `upstream.rs` (upstream 1.9.0 does not
  change them beyond what the app patches already rewrote), and the smoltcp
  0.12 + `socket-tcp-cubic` pin in `Cargo.toml`.
- `CORE_VERSION` -> 1.9.0, crate version -> 1.9.0, `rust-version` -> 1.91 (CI
  installs the latest stable, so no toolchain change is needed).
- `.upstream-baseline/` now caches pristine 1.9.0 copies of all ten patched
  files, so the next automatic upgrade has a real merge base for every one.

### Identity
- versionName 1.2.9, versionCode 13, `PATCHLEVEL` 1.2.9.

**Root cause of the upload stall: nothing on the app->network path was bounded in
time, and the queue that held the upload was never measured.**

The first r7 field log settles it. During an upload the uplink sustained
107 KB/s, the latency probe measured a 7173 ms round trip against a 134 ms
session floor - 7 seconds of pure queue - and at the same moment the uplink
writer had waited 0 times, the device queue peaked at 2 of 64 packets, and
`backpressure` and session `tail-drops` were both 0. So the queue was in none of
the places r5, r6 and r7 clamped.

It was in the three that are bounded only by a byte count - the shared `data_in`
channel (1024 messages x up to 16 KB), `TcpState::pending` (256 KB) and the
`Backlog` (512 KB) - plus the loopback legs of `PsiphonSocksFront`, which Android
autotunes into the megabytes. A byte count is a latency bound only if you know
the rate, and nothing here knew the rate: at 107 KB/s those queues add up to
about **eight seconds**, in front of the ONE Psiphon SSH connection that carries
the whole device.

Then the flow went 9.4 s with nothing acknowledged, recovered 2.6 s inside r7's
12 s reset deadline, and the session stayed connected and useless - uplink 6-18
KB/s for three minutes while the badge read a healthy 140-260 ms - until the user
reconnected by hand. Full analysis and log evidence in
`docs/LIVE_STREAM_STALL_1.2.8-r8.md`.

### Fixed - per-flow uplink admission, measured in time

- Every flow carries a `FlowCredit`. The app may only have
  `handed-to-the-stack - acknowledged-by-the-peer` bytes in the pipe, capped at
  that flow's **own measured drain rate x 500 ms**, clamped to
  `[48 KB, tcp_tx_buf()]`. Past it the SOCKS5 reader stops reading, so
  backpressure crosses the loopback leg, reaches hev-socks5-tunnel and the TUN,
  and ends up in the congestion window of the app doing the upload.
- The rate is derived from r7's acknowledged-bytes signal, the only rate here a
  dead path cannot fake. The floor is above this path's bandwidth-delay product
  so admission can never be the throughput limit, and the ceiling is
  `tcp_tx_buf()` so r8 can only ever remove queue that r6 already permitted.
- Measured effect on the reported session: ~53 KB outstanding instead of
  ~900 KB, i.e. ~0.5 s of standing queue instead of ~8 s.

### Fixed - the loopback legs were the next hidden queue

- `PsiphonSocksFront` pins `SO_SNDBUF`/`SO_RCVBUF` to 64 KB per direction on both
  loopback legs. Without it the queue r8 squeezes out of the netstack simply
  reappears in Android's loopback autotuning, which is the same mistake r6 found
  on the WireGuard socket.

### Fixed - a stall that self-recovers is no longer treated as a recovery

- `TCP_DRAIN_STALL_TIMEOUT` 12 s -> 8 s. The observed stall was 9.4 s, so it now
  ends in a reset, and in chained mode a reset costs one automatic Psiphon redial
  instead of a manual disconnect and reconnect.
- New `STALL_FLAP_LIMIT`: two stalls of >=3 s inside 60 s reset the flow even when
  each one recovered on its own.

### Fixed - the telemetry reported samples where it needed maxima

- `[netstack]` gains `uplink peaks: outstanding N B, pending N B, socket N B,
  budget N B`. `outstanding` and `pending` were never reported before, all three
  are window high-water marks rather than end-of-window samples, and `budget`
  states the rule admission is enforcing. `socket send-queue 3792 bytes` logged
  in the same second as a 4803 ms probe is exactly the reporting gap this closes.

Version stays 1.2.8 / versionCode 12. Patch level only.

## 1.2.8-r7

**Root cause of the live-stream / dubbing stall, after six rounds of misses.**

The netstack measured flow liveness on the wrong event. `TcpState::last_progress`
was refreshed whenever a socket *accepted* bytes into its send buffer, which only
proves the socket agreed to remember them, not that the peer received anything.
A flow whose peer had gone silent therefore looked healthy until its 128 KB send
buffer was 100% full, and `reap_wedged` only ever considered flows in
`backlog.blocked`, which such a flow never enters. The r6 field log shows the
worst flow at exactly 131072 bytes queued emitting one packet in fifteen seconds
with `backlog 0`, `backpressure 0`, `tail-drops 0` and no warning at all. The
30 s wedge clock only started when the buffer saturated; it would have fired 14 s
after the session ended.

In a chained `Aether -> Psiphon` session Psiphon multiplexes the whole device
over one SSH connection, so that single dead flow is every app on the phone:
download collapses while upload trickles on keepalives, which is the reported
symptom exactly.

- Liveness is now derived from **acknowledged bytes**
  (`accepted_total - send_queue()`), the only signal here that requires the far
  end to have actually received data. A naive "did the send queue shrink" check
  was rejected: a saturated upload pins its queue at the buffer limit and would
  have been reset while working perfectly.
- Non-draining flows are reported at 3 s and reset at 12 s, sized against the
  session's measured 142-238 ms RTT.
- `reap_wedged` now scans all flows, before the `backlog.blocked` early return
  that made this case unreachable.
- New `[netstack]` telemetry: `unacked-for <dur> (worst flow), N flow(s) not
  draining` - distinguishes a deep queue that is moving from one that is dead.
- Latency badge: r6 fixed the post-re-warm sample and left the steady-state one
  unfiltered and unlogged, which is why the log only ever held 142-238 ms while
  the screenshot showed 6442 ms. It now keeps a session floor, re-checks an
  outlier once and reports the lower sample, and logs every reading.
- r6's uplink `SO_SNDBUF` clamp is confirmed correct and live (`writer waited 0`
  with the uplink peaking at 146 KB/s); it was simply not this bug.

Version stays 1.2.8 / versionCode 12. Patch level only.

# Changelog

## 1.2.8-r6 - the uplink queue was in the kernel

Version unchanged (**1.2.8**), patch level `1.2.8-r6`. Full analysis with log
evidence in `docs/LIVE_STREAM_STALL_1.2.8-r6.md`.

The r5 provenance work paid off: the field log is stamped `1.2.8-r5`, so for the
first time every previous conclusion could be tested. They all came back negative
- `backlog 0`, `tail-drops 0`, no starvation, no watchdog rotation, no teardown -
on a session where a fresh dial cost 5694 ms and the badge read 3092 ms.

### Fixed - ROOT CAUSE: a 7 MB kernel send buffer disabled every throttle above it

- The 1.2.8 media-stall fix set `SO_RCVBUF` **and** `SO_SNDBUF` from one figure, so
  every WireGuard socket got a 7 MB send buffer (two of them in series in gool
  mode). `send()` on a datagram socket is only backpressure when that buffer is
  full, and 7 MB never is - so the writer never waited, the mpsc never filled,
  `StackDevice.tx` never reached its gate, `transmit()` never refused, and CUBIC
  was still being shown an infinite, lossless link. `backpressure 0` on all 96
  telemetry lines of the r5 log was the receipt, not a clean bill of health.
- `sysprofile` splits the figure: receive stays generous (that half was right and
  it prevents real loss), send becomes a latency budget - 128 KB on a high tier,
  ~250 ms of a mobile uplink, down from 7 MB. A kernel that refuses to shrink it
  now logs a WARN instead of silently restoring the bug.
- The WireGuard writer uses `try_send` + `writable()`, so a full uplink is a timed,
  counted wait that propagates all the way to the congestion window.
- Netstack TCP send buffer 512 KB -> 128 KB. In chained mode the whole device rides
  ONE flow (`tcp flows=1`), and 512 KB in front of it is ~10 s of a live dubbing
  uplink.

### Fixed - the telemetry was pointed at the wrong side of a syscall

- New `[uplink <peer>]` line per tunnel per 15 s: packets, KB/s, the granted
  `SO_SNDBUF`, and how often and how long the writer had to wait. This is the
  measurement that was missing in r2, r3, r4 and r5.
- `[netstack]` gains `socket send-queue N bytes (worst flow M)` - what smoltcp has
  accepted but not yet put on the wire. `backlog` (always 0) is what has not been
  handed to a socket. Together they separate "nothing to send" from "cannot send".

### Fixed - the latency badge reported its own dial as the ping

- The first round trip on a just-re-warmed session rides a connection whose
  handshake was itself queued. The r5 log: `setup 5694 ms, round trip 3092 ms`,
  then 35 s later `setup 327 ms, round trip 191 ms` on the same path. r6 takes a
  confirming sample and reports the lower of the two, logging both - the gap
  between them is the uplink queue depth.

### Fixed - the same CI time bomb r5 found, in three more files

- `sync-core.sh` discovers app patches from `AETHER-APP-PATCH` markers and deletes
  everything else on a core upgrade. `upstream.rs`, `quic.rs` and `wireguard.rs`
  carried no markers and now hold the r6 fix, so the first upstream release would
  have removed it with a green build. All three are now wrapped.

## 1.2.8-r3 - live-stream stall, root-caused

Version unchanged (**1.2.8**). Full analysis with log evidence in
`docs/LIVE_STREAM_STALL_1.2.8-r3.md`; it also documents why the r2 diagnosis was
wrong.

### Fixed - the app was tearing down its own working session

- Watchdog and latency probes dialled anycast resolvers on **TCP/53**, a port a
  large share of Psiphon exits refuse. Every check therefore failed on a healthy
  tunnel. Probes now do a real TLS handshake on **443**.
- A failed probe could rebuild the whole session on its own. It now also requires
  the tunnel core's byte counters to be flat: a path moving traffic is not wedged.
  The r2 log tore down a session that had moved 703 KB.
- The pipeline probe raced `PsiphonHealth`'s deliberate exit rotation and killed
  the session 3.9 s into the repair. It now stands down while a rotation settles.
- Probe destinations are registered as self-probes and can no longer count as
  evidence that an exit filters, so the app cannot trigger its own rotations
  (which drop udpgw and kill every UDP/QUIC flow on the device).
- The latency badge tries three endpoints before reporting an error.

### Fixed - the engine serialised uploads against downloads, per packet

- The WireGuard send task took the boringtun session lock once per packet, against
  the socket reader doing the same. Harmless on a download, fatal on a symmetric
  live stream: every packet cost a scheduler round trip. Bursts are now
  encapsulated under one acquisition (`MAX_ENCAP_BATCH = 64`), with no added
  latency.
- The one-shot `obf_sent` mutex is no longer taken for every outbound datagram.

### Fixed - netstack starvation telemetry cried wolf

- The `select!` arm receiving `data_in_rx` did not count as app->network progress,
  and the report triggered on a single inbound packet. r2 therefore reported
  32-second upload stalls on idle tunnels. The signal now requires a genuinely
  busy download direction and a non-empty backlog.

## 1.2.8-r2 - live two-way streams no longer starve the tunnel

Fix pass inside 1.2.8; the version stays **1.2.8**. Full analysis:
`docs/LIVE_STREAM_STALL_1.2.8-r2.md`.

- **netstack: the app->network path was starved by any sustained download.** The
  run loop's `select!` was `biased` with inbound first, so while packets kept
  arriving the upload arm and the open-a-flow arm were never polled at all. One
  18-minute field log: 115 MB received against 6 MB sent, with a symmetric live
  dubbing session running the whole time. Every queue now gets its own per-pass
  budget and the visit order alternates; `select!` is only reached when all of
  them are empty.
- **netstack: starvation is now reported** in one log line instead of having to
  be inferred from a byte ratio.
- **Psiphon front: one udpgw port forward, not two.** A Psiphon tunnel holds only
  the newest one, so the DNS and bulk lanes added in 1.2.7-r3 evicted each other
  219 times in 18 minutes (218 of 218 consecutive dials alternated lane). DNS is
  prioritised inside the single stream instead. udpgw dials are rate-limited with
  exponential backoff and counted.
- **Bufferbloat: the packet handoff queues were application-sized.** 1024 packets
  per direction plus 2048 retained is several seconds of standing local queue on
  a mobile uplink. Capped at 256; throughput is set by smoltcp's socket buffers,
  so nothing is lost but the delay.

## 1.2.8

### Fixed - the mid-video stall, at the root (`docs/MEDIA_STALL_1.2.8.md`)

Reported symptom: `Aether -> Psiphon` connects and browses fine for about a
minute; a YouTube video then pins the ping at ~2000 ms and the session stops
carrying anything at all - not the video, not any other site - while the app,
both stages and every local port stay up. Only a manual disconnect/reconnect
recovered it. The same freeze occurred on plain `Aether`, which is what located
the fault: everything the two modes share is the engine's data plane.

Five defects, all on that shared path, all now fixed.

- **An expired WireGuard session was ignored instead of being replaced.**
  `Tunn::update_timers` reports "this session is finished" exactly once, and the
  timer task pattern-matched only the `WriteToNetwork` arm - the report went in
  the bin. From that moment `encapsulate` refused every packet (a `trace!` line
  each), so the tunnel object, the netstack and the SOCKS5 listener were all
  alive and healthy around a crypto session that was dead. That is the freeze,
  and it was permanent by construction. The engine now carries the peer's keys
  with the session and **re-handshakes in place on the same socket** - the
  netstack, the listener and Psiphon's upstream never even notice. Both the send
  path (a run of refused packets) and the health task (a quiet data plane) can
  ask for it, rate-limited to one attempt every 3 s.
- **One stalled connection froze every other connection.** The netstack's run
  loop kept a single global deferred queue and gated its only app-data intake on
  it - `data_in_rx.recv(), if deferred.is_empty()`. A flow whose peer stopped
  reading therefore blocked the writes of every other flow, DNS included. The
  backlog is now ordered **per flow**: a stuck flow queues behind itself and
  nobody else waits. A flow that accepts nothing for 30 s is reset, and TCP
  keep-alive plus a dead-peer timeout are set on every socket so a black-holed
  connection can no longer live for the length of the session.
- **The tunnel's UDP sockets never got the buffers the app sized for them.**
  `sysprofile` has been computing them (and logging "udp socket buffer=7168KB")
  since 1.2.5, but only the QUIC/MASQUE path applied it: the entire WireGuard
  and gool data plane ran on the OS default. That is a few milliseconds of a 4K
  stream, so the first time the reader was late the kernel began discarding
  datagrams - including the handshake replies the session needed to survive.
- **The socket readers could park indefinitely.** Both the WireGuard reader and
  the gool relay handed packets on with an unbounded `send().await`, so a busy
  netstack made the *only* reader of the socket go deaf, which caused the loss
  in the point above. Handoff is now bounded at 20 ms: late is fine, deaf is not.
- **A congested burst was shredded, not held.** `flush_tx` dropped every
  remaining packet the moment the writer's channel was full. It now holds the
  burst and retries 2 ms later, tail-dropping only past a real queue limit -
  which is where the 2000 ms ping came from in the first place.
- **The watchdog could not see any of this.** Its probe was a bare SOCKS5
  `connect()`, and a connect is answered by the accept path, not the data path:
  a wedged tunnel passed every check forever, which is why nothing ever healed
  by itself. The probe now completes a real **DNS round trip** and requires a
  valid answer. A chained session, which previously only checked "is the Psiphon
  library still running?", now gets the same end-to-end probe aimed at the
  pipeline's own listener every 15 s and rebuilds itself after two dead
  readings.
- **A congestion spike no longer counts as a dead peer.** The data-plane stale
  timeout went from 10 s (shorter than a bad mobile path stalls on its own, so a
  video's first spike tore down a perfectly good gool session, both hops, and
  charged the user a rescan) to 20 s, with two confirmations, and an in-place
  re-handshake attempted at half that.

### Changed
- Version 1.2.8, versionCode 12.
- Watchdog cadence 30 s -> 15 s, failure threshold 3 cycles -> 2: the probe now
  proves something, so it can act sooner. Probe timeout 8 s -> 5 s.


## 1.2.7

### Fixed (app-connectivity pass, version unchanged - `1.2.7-r3` in code comments)
See `docs/PSIPHON_APP_CONNECTIVITY.md` for the full analysis.
- **AI apps, CapCut and the browsers work in `Aether -> Psiphon` again.** Three
  black holes were reported as "you have no internet" by every app that will not
  fall back from HTTP/3, and the reporter's own control experiment named them: the
  same session, USB-tethered to a laptop, opens Gemini instantly - because a
  tether path is TCP-only and IPv4-only.
- **UDP/443 is carried again** (`CARRY_QUIC`). r2 dropped it silently, and SOCKS5
  `UDP ASSOCIATE` cannot return an ICMP port-unreachable, so a dropped datagram is
  indistinguishable from a dead link. r2's two real mechanisms are fixed at source
  instead: **DNS now has its own udpgw stream** (a priority queue could not help,
  because the blocking happens in the kernel send buffer below it), and the bulk
  lane **drops rather than buffers**, which is exactly the loss signal QUIC's
  congestion control needs. A self-expiring breaker still suppresses UDP/443 for
  60 s under a genuine storm.
- **The device is no longer told it has IPv6 by an IPv4-only exit.** A chained
  session's TUN carries no IPv6 *address* while still routing `::/0`, so Android's
  own resolver filters AAAA per-network and every app settles on IPv4 - and the
  front answers AAAA with an empty NOERROR once the exit has proven it cannot dial
  IPv6. That proof is now obtained up front, once, instead of being paid for by the
  first two app flows of every session (65 refused IPv6 flows in one two-minute log).
- **The sanctions page is gone.** `::/0` is routed unconditionally in a chained
  session: an uncaptured IPv6 flow left with the phone's real address, so the site
  answered with a country block while the app still said Connected.
- **"Only Telegram and Instagram open" was name resolution.** The udpgw dial ran on
  the datagram pump thread, holding a lock every association needed - the pump is
  the only reader of the forwarder's UDP socket, so UDP stopped for the whole
  device, DNS included, for the length of the dial. Dialling moved to its own pool,
  both lanes are pre-warmed before the TUN comes up, and the DNS-over-HTTPS
  fallback now pools and reuses connections instead of paying a port forward plus a
  TLS handshake per name.
- **A SOCKS handshake can no longer hang forever.** `openPsiphonStream` read it with
  no timeout at all, so a listener that accepted and never answered parked the
  caller for the life of the session.
- **A refused TCP/853 is named in the log.** That is where Android's Private DNS
  goes; in strict mode there is no fallback, so the device resolves nothing while
  the tunnel is healthy. Not counted as censorship.
- `hev.yaml`: `udp-read-write-timeout` 60000 -> 120000, because a live QUIC
  connection legitimately idles now.

### Fixed (stability pass, version unchanged)
- **The app no longer disconnects itself while healing a bad exit server.**
  `PsiphonHealth`'s last-resort rotation calls `restartPsiphon()`, which stops the
  Psiphon library in place; that fires `onExiting`, which cleared
  `PsiphonTransport.connected`; and the chained supervisor in `AetherVpnService`
  polls `transport.isAlive()` once a second and tore the whole session down the
  moment it read false. The log caught it exactly: `Psiphon: Exiting: {}` at
  08:06:38.535, `hev-socks5-tunnel stop requested` 0.74 s later. `isAlive()` now
  masks a bounded rotation window (`ROTATION_GRACE_MS`, self-expiring), and the
  supervisor additionally requires `TRANSPORT_DEAD_CONFIRMATIONS` consecutive dead
  reads before rebuilding a session.
- **Rotations actually change server now.** The old rotation dropped a working
  tunnel and rebuilt the identical one: psiphon-tunnel-core ranks the last
  connected server first via dial-parameter replay (`isReplay: true`) and server
  affinity (`moved-to-front`), and from the library's point of view a server that
  refuses port forwards is excellent - the tunnel established and bytes moved.
  Log: `rotating off server OsBTQokd` at 08:03:43.044, `ConnectedServer ... OsBTQokd`
  at 08:03:45.759. Convicted servers now go on a session blacklist together with
  their exit region, and a `RESTART` rotation rebuilds the config with
  `DisableReplay`, `EstablishTunnelServerAffinityGracePeriodMilliseconds: 0` and -
  when the exit is Automatic - a round-robin steer to a different *reachable*
  egress region, which is the only hard server filter the library exposes. A
  hand-picked country is never silently changed.
- **Landing back on a blacklisted server is detected instead of ignored.** The old
  code keyed its reset on the active server *changing*, so re-connecting to the
  same id did nothing and it had to re-earn six refusals. Every `ActiveTunnel`
  notice is now a verification point: a known-bad server is re-rotated at once.
- **The rotation budget is no longer burned in three seconds.** Refusals draining
  out of an abandoned tunnel were counted against its replacement - hence
  "refused 6 different destinations" five times inside 300 ms in the log. A
  `SETTLE_MS` grace period per tunnel attributes them correctly, counters for a
  server that is not the active one are dropped, and a server that stays clean for
  three minutes refunds the budget so a long session is not left defenceless.
- **Ping spikes, drop/reconnect churn and low throughput on a filtering server**
  are the same root cause: every rotation dropped the udpgw session and every
  in-flight flow, and the rotations were looping. With the loop closed, UDP (DNS +
  QUIC) survives a rotation and `PsiphonSocksFront.retarget()` follows Psiphon to a
  new local port without ever closing the listener tun2socks is connected to.
- **Persian is right-to-left even when the phone is in English.** `attachBaseContext`
  produced a correct `Configuration`, which is why the text was Persian, but Compose
  lays out from `AndroidComposeView.onRtlPropertiesChanged` - the direction the VIEW
  tree resolved - and the decor view resolves that against `Locale.getDefault()`,
  which the framework rewrites from the activity's own locale list after
  `attachBaseContext` has run. On a Persian phone it wrote back the value we wanted
  and hid the bug; on an English phone it wrote `en` over our `fa` and the whole UI
  laid out LTR. `AetherTheme` now pins `LocalLayoutDirection` from the stored
  language choice (`LanguagePrefs.isRtl`), and `LanguagePrefs.applyLayoutDirection`
  pins the window so dialogs, popups and selection handles agree. Covers dialogs,
  bottom sheets and dropdowns, which read the same composition local.
- `LanguagePrefs.findActivity()` walks out of any `ContextWrapper`, so the language
  switch's `recreate()` can no longer no-op on a wrapped `LocalContext`.

### Added
- **Vazirmatn is bundled** (`res/font/vazirmatn_bold.ttf`) and applied across all
  fifteen Material type roles whenever the app renders Persian, so every screen,
  row, button, dialog and sheet uses it instead of falling through to whatever
  Arabic-script fallback the device happens to ship. Technical readouts (timers,
  rates, IP:port, the diagnostics log) stay monospaced so their columns keep
  aligning. Only the Bold face ships, so every weight is mapped to it explicitly
  to avoid synthetic emboldening; see `ui/theme/Type.kt` to add a real weight ramp.
- **A settings screen** replacing the single collapsible "Advanced settings" card:
  a category list, controls grouped into cards, bottom-sheet pickers with the
  current option ticked, a large collapsing title per page, and the current value
  of each category shown on its row. Reachable from the home screen's top-right
  icon or the side menu. New package `ui/settings/` (`SettingsUi.kt` design
  system, `SettingsScreen.kt` host and pages).
- **In-app language selection** (`data/LanguagePrefs.kt`): Follow the phone /
  English / Persian, applied in `attachBaseContext` so the first frame is already
  correct, and wired into the Activity, the Application, the VPN service, the
  Quick Settings tile and the home-screen widget. Persian sets the layout
  direction to RTL; technical fields stay LTR. On API 33+ the choice is mirrored
  into `LocaleManager.applicationLocales`.
- **`fastEndpointOnly` ("Only reuse a fast endpoint")**, on by default, exposed
  under Transport & anti-DPI. Sends `AETHER_QUICK_RECONNECT_MAX_RTT_MS` /
  `AETHER_QUICK_RECONNECT_MAX_HANDSHAKE_MS`, with a tighter budget for the chained
  hop (`ConnectionProfile.chainedStage`).
- **Automatic Psiphon exit-server rotation** (`transport/PsiphonHealth.kt`) when a
  server refuses many distinct destinations in a sliding window, or its
  `port forward failures` counter climbs past a threshold: `reconnectPsiphon()`,
  escalating to `restartPsiphon()`, rate-limited and capped per session.
  `PsiphonSocksFront.onServerRotated()` drops the udpgw session and clears the
  refusal latch so the new server gets a fresh chance at real UDP.
- A confirmation dialog before "Reset all settings to defaults".

### Changed
- **The colour scheme is pinned.** `AetherTheme` no longer calls
  `dynamicDarkColorScheme()`, which repainted every themed surface from the
  wallpaper on Android 12+ while the connection card pinned brand colours -- so
  half the app was on brand and half was not, differently on every device. One
  hand-built eight-step navy ramp (`ui/theme/Color.kt`) now backs every surface
  role, including Material's `surfaceContainer*` tints.
- **UI performance.** `ConnectionProfile` is `@Immutable` (it was inferred
  unstable because of its `List<String>` members, so no composable taking a
  profile could ever be skipped); settings pages are lazy lists of independently
  lazy sections; the navigation drawer is a menu instead of a host for four live
  panels; the home screen is disposed while settings is open; and profile writes
  are debounced (300ms) with an `onStop` flush instead of one DataStore commit per
  keystroke.
- `ProfileCodec` now carries the in-tunnel DNS servers, both routing-rule lists,
  domain sniffing and its window, the upstream proxy, identity replacement and the
  Zero Trust enrolment fields, so those settings reach the engine at launch. The
  two Zero Trust secrets are deliberately still absent from the Intent payload:
  `AetherVpnService.hydrateSecrets()` reads them from the hardware-backed
  `SecretStore` instead.
- Engine (`native/aether/aether/src/lib.rs`, inside `AETHER-APP-PATCH` markers): a
  cached quick-reconnect endpoint is reused only when it is still fast, not merely
  alive. A field log shows one accepted at `rtt 472ms` while 100-143ms edges had
  just been measured, which roughly halves throughput -- twice over in a chained
  session. Stripping the markers reproduces the pristine upstream file byte for
  byte, and `lib.rs` is registered in `PATCHED_FILES`.
- The backend and exit-country help text is localised; it was hard-coded English.

### Fixed
- **`Aether -> Psiphon` connected and opened nothing.** hev-socks5-tunnel carries
  all UDP (and therefore all device DNS) over SOCKS5 `UDP ASSOCIATE`, and
  psiphon-tunnel-core's local SOCKS proxy is CONNECT-only: `socks5ReadCommand:
  SOCKS message field command was 0x03, not 0x01`, 636 times in one 50-second
  session, 267 bytes transferred, no page loaded. New `PsiphonSocksFront` binds
  `127.0.0.1:1825` (the port tun2socks talks to) and chains onto Psiphon at
  `127.0.0.1:1827`, carrying real UDP over Psiphon's remote udpgw
  (`127.0.0.1:7300`, one shared stream multiplexed by connection id, exactly as
  badvpn's `SocksUdpGwClient` does) so DNS *and* QUIC work. If a server refuses
  the udpgw port forward, port-53 datagrams fall back to DNS-over-TCP through the
  same tunnel and non-DNS UDP is dropped, so name resolution never depends on the
  intercept being available.
- **The VPN interface terminated the Psiphon tunnel seconds after connecting.**
  `setVpnMode(false)` made PsiphonTunnel's network monitor read our own TUN as a
  network change (`NetworkMonitor: set current active network VPN with DNS` ->
  `terminated tunnel` -> `tunnel failed`) and adopt the TUN's advertised resolvers
  as its own, which pointed back through the tunnel it was establishing. VPN mode
  is now declared; PsiphonTunnel 2.x runs no tun2socks or routing of its own, so
  the flag only affects network monitoring and DNS selection.
- Both chained transports now report their FRONT's port from `start()`, so
  tun2socks can never again be handed a listener that refuses `UDP ASSOCIATE`.

### Added
- **A fifth self-test step: Device DNS (SOCKS5 UDP).** The four existing checks
  stayed green through the entire broken Psiphon session, because the DNS check
  resolves via a `CONNECT` carrying a hostname (resolved remotely on the TCP
  path) while the device uses `UDP ASSOCIATE`. The new check speaks the same
  protocol hev does against the same port and **gates the Connected state**.
- Engine core upgraded to **1.8.0** (world-reachable listener warning,
  non-blocking HTTP head read, upstream-proxy SOCKS5 auth hardening, and a fix
  for the tokio "JoinHandle polled after completion" panic in Gool teardown).
  `cli.rs` / `config.rs` are byte-identical to 1.7.0, so there are no new engine
  flags to surface. Both manual-range patches were rebased onto the new sources
  and, apart from the `AETHER-APP-PATCH` blocks, the vendored tree is
  byte-identical to upstream. Baseline floor raised to 1.8.0 and a pristine
  1.8.0 merge base cached in `native/aether/.upstream-baseline/`.
- **An animated ping-strength meter** on the latency slide, which otherwise left
  most of its width empty: a travelling waveform of rounded bars whose height,
  brightness and colour follow the last probe (mint / amber / rose) with a
  quality word beside it. The travel speed is fixed on purpose - deriving it from
  the latency changed the animation spec on every probe, which restarts an
  infinite transition and made the wave jump once every four seconds.
- The latency slide keeps the **last good reading**. `PingMonitor` publishes
  `ms = -1` while a probe is in flight, so the value used to blink to an ellipsis
  and the meter collapse for a moment every four seconds.
- Strings for the meter in English and Persian (`meta_ping_strength`,
  `ping_quality_*`).

### Removed
- **Single-hop `Psiphon` and `Tor` backends.** Neither could get its own first
  hop past the networks this app targets, so both sat on "Connecting" and timed
  out while the chained modes connected in seconds. Saved profiles are MIGRATED,
  not reset: `PSIPHON` -> `AETHER_PSIPHON`. The Tor names resolve to `AETHER`,
  because this release removes the Tor mode entirely - see the next bullet.
- **The Tor backend, entirely.** Both the protocol and the chained
  `Aether -> Tor` mode are gone from Advanced -> Network backend, and so is
  everything behind them: `transport/TorTransport.kt`,
  `transport/TorSocksFront.kt`, `scripts/build-tor.sh`, the packaged `libtor.so`
  executable, `TunnelConfig.TOR_SOCKS_PORT` / `TOR_DNS_PORT` (1822 / 1823), the
  `ExternalKind.TOR` and `TransportBackend.AETHER_TOR` enum values, the
  release-CI "Build Tor native core" step and the Tor entries in the
  third-party notices. Tor carries TCP streams only, so every datagram the
  device sent had to be answered out of Tor's own `DNSPort` and everything else
  dropped (no QUIC, and DNS depending on an adapter faking a protocol Tor does
  not speak), and `Aether -> Tor` paid a full Tor bootstrap *through* the Aether
  handshake, which was slow enough to read as a hang. Psiphon delivers the same
  thing - a foreign exit behind Aether's obfuscated first hop - carries real UDP
  and comes up in seconds, so it is the one external transport that ships.
  Backends are now **Aether** and **Aether -> Psiphon (chained)**.
- Saved profiles: a stored `TOR` / `AETHER_TOR` resolves to plain `AETHER`, NOT
  to `AETHER_PSIPHON`. Migrating a profile that asked for a Tor exit onto a
  different provider's network would swap one trust model for another without
  telling the user. `PSIPHON` still migrates to `AETHER_PSIPHON`.
- The two Tor Kotlin files are listed in `.github/removed-sources.txt`, so a
  release built on top of an older checkout purges them instead of failing to
  compile against symbols that no longer exist.

### Changed (UI pass 2, same version)
- **The home screen no longer scrolls: it fits, by construction.** The column was
  a `verticalScroll`, so on a shorter screen - or with a larger system font, or in
  Persian where several strings wrap - the end of the connection card sat below the
  fold and part of it stayed under the navigation bar. Hand-tuned `Spacer` heights
  only moved that problem to the next screen size. New
  `ui/components/FitToHeight.kt` measures the content against an unbounded height,
  and when it is taller than the viewport it overrides `LocalDensity` for the whole
  subtree by one measured factor - type, paddings, icons, radii and stroke widths
  together - until it fits. The search starts at `1f`, only ever shrinks (so it is
  monotone and cannot oscillate), converges in one or two passes inside the same
  frame, bottoms out at `0.55f`, and re-runs when the viewport or the system font
  scale changes. Density is overridden rather than `graphicsLayer { scaleX = ... }`
  on purpose: this is a real layout at a real density, so text is rasterised at the
  size it ends up being and the scaled UI is exactly as sharp as the unscaled one.
- **The travelling ring around the connect button is gone.** Two light shows on
  one screen competed for the eye, its bloom needed a 220 dp box for a 150 dp
  button (70 dp of padding at the top of the screen, almost exactly the height the
  content block was missing at the bottom), and it cost a second set of additive
  strokes per frame. The card edge keeps the light. Button geometry came down with
  it: box 220 -> 190 dp, disc 150 -> 132 dp.
- **A tick replaces the bolt on the connected button** (`Icons.Rounded.Check`,
  84 dp). A bolt reads as "power", which is what the idle button already says; a
  tick reads as "you are through". The soft additive core behind the glyph stays.
- **The travelling light runs in the PRIMARY colours:** red, green, blue, yellow -
  one per lap, wrapping. The previous six-colour palette (mint, cyan, azure,
  violet, rose, amber) was six tints of the same cool corner of the wheel, three
  of them barely distinguishable at hairline width on a navy card. The two cool
  primaries are luminance-trimmed rather than mathematically pure, because this
  light is drawn additively on `#0A0E1A`: pure `#0000FF` has too little luminance
  to read as light and pure `#00FF00` clips its own bloom to white.
- **The light is drawn at higher fidelity.** The bloom was three strokes over a
  4.4x falloff with a highlight lerped 42% toward white, which at peak amplitude
  stacked into a hard-edged white blob with visible alpha banding - the "pixelated"
  look. It is now five graded strokes over a 3.2x falloff, a smoothstep on the
  amplitude, a peak alpha held below saturation, a 30% highlight, and
  `StrokeJoin.Round` on every stroke (a mitred join spiked visibly on the card's
  26 dp corners). The light is also drawn 1.5 dp wide instead of exactly 1 dp,
  which lands on a device-pixel boundary far more often.
- **The card's vertical rhythm tightened again** to spend less of the fit budget:
  inner padding 18 -> 16/15 dp, section spacing 14 -> 11 dp, status 30 -> 27 sp,
  timer 38 -> 33 sp, ping meter 26 -> 22 dp, IP pill and meta rows one dp tighter.
- **About is reordered: this edition first.** The Android app / GUI author
  (QW-AI-Code) and its feature list open the panel, with the upstream Aether engine
  (Cluvex Studio) credited underneath, and the chained `Aether -> Psiphon`
  transport is listed among what this edition adds.
- **Nothing on the button animates unless it must.** The halo pulse is composed
  only while connected and the sweep only while busy, both read inside draw/layer
  lambdas, so an idle screen holds no frame subscription and a frame costs a redraw
  rather than a recomposition.

### Security (audit, same version)
- **The LAN proxy bridge now fails closed.** `ShareBridge.start`/`startSync` had
  `localOnly = false` as their DEFAULT, so any call that omitted the argument bound
  an unauthenticated SOCKS5 + HTTP proxy on `0.0.0.0:10810/10811` for the whole
  network. Every existing caller passed the flag, so behaviour is unchanged - but
  the default is now `true` (loopback) and the two LAN call sites in `SharePanel`
  ask for exposure explicitly.
- **Backup rules are actually wired up.** `res/xml/backup_rules.xml` existed and
  was referenced by nothing; `android:allowBackup` was the only thing standing
  between the data dir (manual endpoints, diagnostics log, sealed secrets) and a
  cloud backup. The manifest now declares `android:fullBackupContent` and a new
  `res/xml/data_extraction_rules.xml` denies both cloud backup and device-to-device
  transfer for every domain on Android 12+.
- **Dead export surface removed.** `res/xml/file_paths.xml` described a
  FileProvider that went with the in-app updater in 1.2.2. Deleted and registered
  in `.github/removed-sources.txt`.
- **Orphaned Tor build script removed.** `scripts/build-tor.sh` survived the Tor
  removal, cross-compiled a `libtor.so` nothing loads, and was documented as
  already deleted. Deleted for real, registered for purge, and the purge
  allowlist now accepts `scripts/` paths.
- Full findings, including the accepted risks and what was verified clean, in
  `docs/SECURITY_AUDIT_1.2.7.md`.

### Changed
- **Protocol, endpoint and latency each get their own full-width slide** inside
  the connection card, replacing the single three-column strip. Each column was
  a third of a phone screen wide, which is not enough for the values it carried:
  `WIREGUARD` rendered as `WIREGUARD ...` and an endpoint (`ip:port`, 21 chars
  for IPv4, more for IPv6) was almost always `...`. Label left, value right with
  the whole card width, two lines for a long endpoint. Everything stays inside
  the same card and the card keeps its proportions.
- **The layout above the card tightened to pay for that height.** Screen padding
  32 -> 18 dp, the gap under the title 28 -> 10 dp and the gap under the connect
  button 28 -> 6 dp, so the button and the card both move up instead of the block
  sliding off the fold. The card's internal rhythm went 16 -> 14 dp.
- **The travelling border light runs one colour per lap.** It was a fixed
  mint-to-cyan blend; it now holds one colour for a full lap of the perimeter and
  takes the next colour of the palette (mint, cyan, azure, violet, rose, amber)
  on the next lap, wrapping back to the first after the last. Implemented as ONE
  `animateFloat` from 0 to the number of colours, so the lap index is its integer
  part and the wrap has no seam: no crossfade to schedule, no second animation to
  resynchronise.
- **The connect button shows the same light show once connected.** The same
  bands, colours and lap, from the same `rememberGlowCycle` clock, drawn around
  the button's disc by the same helper the card edge uses (new
  `ui/components/GlowCycle.kt`), so the two surfaces cannot drift apart. Nothing
  subscribes to a frame callback while disconnected.
- **One big bolt.** The connected icon went from a 58 dp spark in a 150 dp disc
  to a single 96 dp bolt with a soft additive core behind it.

### Added (earlier in this release)
- Backend selector for Aether, Psiphon, and Tor. Both Tor entries were removed
  later in the same release: what ships is Aether and the chained
  `Aether -> Psiphon` (see Removed above).
- **Chained backends: `Aether -> Psiphon` and `Aether -> Tor`** (the Tor one was
  removed again before release). The Aether engine
  comes up first as a local SOCKS5 proxy, the second stage dials out through it,
  and the TUN is pointed at the second stage. The public exit IP becomes Psiphon's
  or Tor's while the only hop the local network sees is Aether's obfuscated
  transport. Psiphon rides it via `UpstreamProxyUrl`, Tor via `Socks5Proxy` in
  `torrc`. Selectable under Advanced -> Network backend.
- Exit-country selector with automatic mode, now with **flag emoji** next to every
  country name (globe for Automatic) and a much wider list: the practical union of
  Psiphon's egress regions and the countries Tor has reliable exits in.
- Psiphon Tunnel Core 2.0.39 integration and embedded signed server-entry bootstrap list.
- Direct Tor runtime with DNS-aware SOCKS front and optional non-strict ExitNodes preference. (Removed again in this release.)
- Pinned Tor native build script and release-CI build step for arm64-v8a and armeabi-v7a. (Removed again in this release.)

### Fixed
- **Crash on Tor disconnect (`FATAL on thread 'tor-log'`).** The Tor stdout reader
  was an unguarded `forEachLine` on a bare thread. Stopping Tor closes that stream
  mid-`readLine`, and the resulting `IOException("Stream closed")` reached the
  process-wide handler and killed the app during an ordinary teardown. The reader
  is now guarded and carries its own uncaught handler; an expected end-of-stream is
  logged as the normal event it is.
- **Tor never reported Connected.** Tor opens its `SocksPort` ~100 ms after launch,
  long before it has a consensus, and that open port was treated as readiness. The
  TUN and the four-step self-test therefore ran against a Tor still at
  "Bootstrapped 30%", and the session was torn down as a failure at 66% while Tor
  was working and simply needed another minute. `TorTransport.start()` now waits for
  `Bootstrapped 100%`, reports the percentage as it climbs, and allows 300 s.
- **Psiphon never reported Connected.** Three separate causes:
  - A pinned exit country is a hard filter in psiphon-tunnel-core. The transport now
    retries with an automatic exit (and a fresh datastore) instead of hunting for an
    egress that will never appear.
  - The service aborted the session unless Psiphon bound exactly port 1819
    (`check(port == SOCKS_PORT)`). It now follows the port Psiphon reports through
    `onListeningSocksProxyPort`, and only warns when that differs.
  - The external path did not wait for the previous session's listener to be
    released before starting, so a leftover socket pushed Psiphon onto a random port.
    It now waits, exactly like the Aether path already did.
- The self-test grace window for Psiphon, Tor and chained sessions is 150 s instead
  of 90 s. Even a fully bootstrapped Tor needs seconds to build its first exit
  circuit, and a chained session pays both hops' warm-up.
- Psiphon's datastore moved out of `filesDir` root into `filesDir/psiphon`, so a
  wedged datastore can be reset without touching `hev.yaml` or the Tor directory.
- Proxy mode and LAN sharing now relay into the *finished* pipeline's SOCKS port.
  Previously `ShareBridge` was hardcoded to 1819, which in a chained session would
  have quietly shared the first hop's exit.

### Architecture
- External transports own only their upstream SOCKS endpoint and report the port they
  actually bound. AetherVpnService remains the single owner of VPN consent, TUN,
  split tunneling, kill switch, diagnostics, forwarding, notifications, and teardown.
- `TransportBackend` gained `AETHER_PSIPHON` and `AETHER_TOR` plus the
  `usesAetherEngine` / `usesExternal` / `isChained` predicates, so the service and
  the UI branch on capability rather than on an enum identity check.
- Both hops of a chained session are supervised; a dead stage 1 rebuilds the session
  instead of leaving a Connected badge over a proxy that cannot dial.
- Profile DataStore and Intent codec persist backend and exit-region selections by
  NAME, so the two appended enum values are backward compatible with saved profiles.

### Versioning
- Version name: 1.2.7 (unchanged)
- Version code: 11 (split outputs derive monotonic ABI-specific codes from this base).

## 1.2.7 — fix pass (r2): Psiphon media stalls + security audit pass 3

Version unchanged (1.2.7 / versionCode 11). No new UI, no new permissions.

### Fixed — `Aether + Psiphon` collapsed during video playback
Root cause was four compounding defects, all reproduced from the field log
(`docs/PSIPHON_MEDIA_STALL.md` has the annotated timeline):

- **QUIC is no longer carried over udpgw.** A video stream on UDP/443 shared one TCP port forward
  with every DNS query on the device: head-of-line blocking plus a TCP-over-TCP congestion
  meltdown. Dropping UDP/443 from the first datagram makes browsers fall back to HTTP/2 over TCP,
  where each flow gets its own Psiphon channel. DNS, VoIP and every other UDP protocol are
  untouched.
- **The udpgw writer can no longer block the UDP pump.** Frames were written straight from the
  datagram pump under one lock with a blocking write, so a congested tunnel stopped UDP for the
  whole device, DNS included. There is now a bounded, prioritised queue (DNS has its own lane), a
  dedicated writer thread, batched flushes, and oldest-first drop for bulk frames.
- **IPv6 flows fail instantly instead of dying at the exit.** The TUN offers IPv6 while Psiphon
  exits are IPv4-only, so a video player's fan-out produced 26 refused port forwards in 4.2 s. The
  front now latches the verdict after two probes and answers IPv6 locally with `host unreachable`.
  Leak protection is unchanged.
- **The health watchdog no longer rotates on load.** "25 refused port forwards in 45 s" measured how
  busy the session was, not whether the server filtered, and it tore a healthy tunnel down 4 s into
  a video, three times in two minutes. The tunnel's own counter is now corroborating only: a
  rotation also requires refusals for 3 distinct destinations, and structural refusals (IPv6, the
  udpgw intercept, TCP/53) are no longer reported as censorship.

Also: DNS falls back to **DNS-over-HTTPS on 443** when a server refuses TCP/53 (which was burning
one refused port forward per lookup); udpgw connection ids can no longer collide and cross-deliver
another flow's replies; the `conids` map is bounded; `hev.yaml` gets `connect-timeout: 12000`,
`udp-read-write-timeout: 60000` and `limit-nofile: 65535`; and the latency badge finally measures
the port the finished pipeline exposes instead of stage 1's listener.

### Security (audit pass 3 — `docs/SECURITY_AUDIT_1.2.7-r2.md`)
- **CRITICAL, reported not silently changed:** the release signing key is committed to the repo
  (`.github/ci-keystore.jks.b64`, password in `app/build.gradle.kts`). Anyone can sign an APK that
  installs over this app as a legitimate update. Needs a key rotation; release builds using that
  key now print a loud warning.
- The exit-IP badge no longer comes from cleartext HTTP first — an on-path attacker could forge
  "you are protected". The authenticated provider is tried first.
- The persisted diagnostics log no longer records per-flow destinations (partial browsing history)
  or the engine's argv values (Zero Trust team name, pinned gateways, resolvers, routing rules).
- Unsynchronised iteration of a shared flow map fixed.

## 1.2.8-r5

- **Root cause of the r4 round: the r4 build was never tested.** Five independent strings in the field log belong to the r3 engine and Kotlin. See docs/LIVE_STREAM_STALL_1.2.8-r5.md.
- **Build identity added end to end.** PATCHLEVEL -> build.rs stamp inside libaether.so -> verified by build-natives.sh on the stripped .so -> verified again by CI inside the packaged APK -> cross-checked at runtime by BuildProvenance -> shown in the About card. versionName stays 1.2.8.
- **CI could no longer silently revert engine patches.** sync-core.sh discovers patched files from AETHER-APP-PATCH markers instead of a hand-written list (which omitted Cargo.toml, netstack.rs, sysprofile.rs and build.rs), and a patch that cannot be rebased now fails the build.
- **Netstack device backpressure (root cause #2).** StackDevice::transmit() returned Some() unconditionally over an unbounded queue, so every drop happened after smoltcp had recorded the packet as sent and was invisible to the congestion controller r4 enabled. transmit() now refuses at MAX_DEVICE_TX and flush_tx holds bursts instead of shredding one packet per pass.
- **MAX_BACKLOG_BYTES 8 MB -> 512 KB**; **first netstack telemetry line at 2 s** with device queue peak and backpressure counters.
- **Hard RTT floor on endpoint selection**: a discarded cached endpoint is kept when the scan fails to beat it, so rejecting a 396 ms cache can no longer land on a 475 ms replacement.
