# Security audit — Aether 1.3.0

**Score as audited: 88 / 100.**
**Score after the remediation shipped in this same build: 93 / 100** (see §6).
Both are weighted over nine areas.
**Target of review:** the shipped 1.3.0 tree — Kotlin app (`app/src`, 87 files,
≈28 200 lines), Gradle build and resource configuration, `AndroidManifest.xml`,
the vendored Rust engine (`native/aether`, core 2.0.0) and its lockfile, the
prebuilt Psiphon library, and the release workflow.
**Method:** manual code review of every security-relevant path, plus mechanical
scans (secret patterns, weak-crypto constructors, TLS trust customisation,
`VpnService.Builder` calls, logging sinks, permission and manifest flags,
`PendingIntent` mutability, dependency inventory).

> **The score is not comparable with the 79 / 100 of the 1.2.9 audit.** App
> signing and update compatibility are **out of scope** for this audit by product
> decision: 1.3.0 keeps the signing identity of 1.2.9 so that it installs over an
> existing install without an uninstall, and no signing-related item is scored
> below. The 1.2.9 audit scored that area, and it was what held that report to 79.

---

## 1. Scores

| # | Area | Weight | Score | Weighted | After fixes | Weighted |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | Secrets & key management | 15 | 95 | 14.25 | 95 | 14.25 |
| 2 | Cryptography, TLS & MitM resistance | 14 | 92 | 12.88 | 95 | 13.30 |
| 3 | Data-leak risk (DNS, IPv6, tunnel bypass) | 20 | 88 | 17.60 | 95 | 19.00 |
| 4 | Local storage at rest | 13 | 94 | 12.22 | 94 | 12.22 |
| 5 | Permissions & OS configuration | 8 | 98 | 7.84 | 98 | 7.84 |
| 6 | Logging & diagnostics | 8 | 96 | 7.68 | 96 | 7.68 |
| 7 | Code quality & network configuration | 10 | 78 | 7.80 | 84 | 8.40 |
| 8 | On-device exposure (screen, clipboard) † | 6 | 65 | 3.90 | 92 | 5.52 |
| 9 | Supply chain & build integrity † | 6 | 72 | 4.32 | 84 | 5.04 |
| | **Total** | **100** | | **88.49 → 88** | | **93.25 → 93** |

Why each "after" number moved is in §6, finding by finding. Areas 1, 4, 5 and 6
had no open finding against them and are unchanged. Area 7 stops at 84 because R8
is still off (F-6), area 9 at 84 because the actions are still tag-pinned (F-9) and
the Psiphon binary now has a provenance *record* rather than a verified origin.

† Areas 8 and 9 are additions to the seven-point brief: an audit of a
circumvention tool that stops at the network layer misses the two places this
class of app actually loses secrets — the screen, and what is built into the APK.

---

## 2. Findings

Severity is the risk to a user of this app in its intended threat model (a
censored, monitored network; possibly a seized or rooted device).

| ID | Severity | Finding | Where | Status |
| --- | --- | --- | --- | --- |
| F-1 | Medium | The kill-switch lockdown interface routes IPv6 only when IPv6 leak protection is on | `AetherVpnService.kt:1657-1660` | **Fixed** |
| F-2 | Medium | Kill switch is off by default | `Profile.kt:183` | **Fixed** |
| F-3 | Medium | No `FLAG_SECURE`: the API-key field, the LAN password and the diagnostics log are screenshot- and recents-visible | no occurrence in `app/src` | **Fixed** |
| F-4 | Low | Secrets copied to the clipboard are not marked sensitive | `SharePanel.kt`, `DiagnosticsPanel.kt`, `CrashReportActivity.kt` | **Fixed** |
| F-5 | Low | Two of three geolocation providers are cleartext HTTP on a raw socket | `NetProbe.kt:87-88` | **Fixed** |
| F-5b | **Medium** | The country-refinement lookup was still cleartext ip-api **and reachable from the direct, untunnelled path**, so it emitted a plaintext, app-shaped request containing the user's real public IP on every disconnected IP refresh | `NetProbe.kt` `refineCountry`, `fetchIpInfoDirect` | **Fixed after the audit** |
| F-6 | Low | R8/minification and resource shrinking are disabled for release | `app/build.gradle.kts:257-258` | Open (deliberate) |
| F-7 | Low | No dependency-vulnerability or secret scanning in CI | `.github/workflows/build.yml` | Partly fixed |
| F-8 | Low | A 44 MB prebuilt Psiphon `.aar` is committed instead of built or verified against upstream | `app/libs/psiphontunnel-2.0.39.aar` | Documented, not verified |
| F-9 | Info | CI actions are pinned by tag, not by commit SHA | `.github/workflows/build.yml` | Open |
| F-10 | Info | Certificate pinning is absent (deliberate, documented) | `res/xml/network_security_config.xml` | Accepted |

The findings below describe the code **as audited**. What changed afterwards is in
§6, so that the report keeps a record of the state that was reviewed rather than
quietly rewriting itself.

### F-1 — IPv6 is unrouted in lockdown when the user disabled v6 protection

`ensureLockdownTun()` builds the blackhole TUN that holds traffic while the
tunnel is not forwarding. It adds the IPv6 address and `::/0` route **inside**
`if (profile.ipv6LeakProtection)`. The normal tunnel builder is stricter: it
forces `::/0` whenever the backend is chained, regardless of that switch
(`AetherVpnService.kt:1256`). So a user who turned IPv6 protection off to fix a
broken v6 network keeps a v6 path open during exactly the window the kill switch
exists for. The lockdown TUN should route `::/0` unconditionally — a blackhole
has no connectivity to break.

### F-2 — Kill switch default

`killSwitch = false` and `strictKillSwitch = false` in `ConnectionProfile`. IPv6
leak protection defaults to `true`, which is the right instinct; the kill switch
is the one that decides whether a dropped tunnel means "no traffic" or "traffic
in the clear", and on a circumvention tool the safe default is on.

### F-3 — No screen-capture protection

There is no `FLAG_SECURE` and no `setRecentsScreenshotEnabled(false)` anywhere in
the app. The Gemini API key entry, the LAN sharing password and the full
diagnostics log (exit IPs, endpoints, WARP enrolment handle) are all readable in
a screenshot, in the recents thumbnail, and by any accessibility or screen-record
path the user has granted elsewhere. The rest of the app takes the "seized
device" threat seriously enough to encrypt files at rest (§4); the screen is the
same threat with a shorter path.

### F-4 — Clipboard

Copy buttons exist for the LAN proxy credential, the verbatim diagnostics log and
the crash dump. None of the `ClipData` objects sets
`ClipDescription.EXTRA_IS_SENSITIVE`, so on Android 13+ the value appears in the
clipboard preview, and on any version it is readable by the default IME and by
clipboard-history utilities.

**Correction to an earlier draft of this report:** it listed a copy button for the
Gemini API key (`AiPages.kt:90`) and one in `SettingsScreen.kt:1039`. Re-checked:
`AiPages.kt` only *reads* the clipboard (`getText`, to paste a key in) — there is no
copy of the API key anywhere — and the `SettingsScreen` copy is
`127.0.0.1:<port>`, a loopback address that is not a secret. The finding stands for
the three sites named above and is smaller than first written.

### F-5 — Cleartext geolocation fallbacks

`NetProbe.PROVIDERS` races `www.cloudflare.com:443` (TLS, hostname verified),
then `ip-api.com:80` and `1.1.1.1:80` in cleartext. These carry no credential and
no user data, and they are raw sockets, so `usesCleartextTraffic="false"` does not
apply to them. What an on-path observer gets is the fact that this device asked
"what is my IP" in a recognisable form — a weak but real fingerprint of the app
on a network where the app's presence is the sensitive fact. `1.1.1.1` also
serves `/cdn-cgi/trace` over TLS; using it would cost nothing.

### F-5b — The refinement lookup this audit missed (found after the release)

**This item was not in the original report. It is recorded here because the fix
for F-5 was incomplete and the report's own wording concealed it.**

F-5 fixed the provider LIST. It did not touch `refineCountry()`, and the closing
sentence of the F-5 remediation — "country refinement is still ip-api over HTTP
and is still informational only" — reads like a caveat about a label. It was a
description of a leak.

What the code actually did:

* `refineCountry()` ran after EVERY successful probe, on all three call sites, to
  make two geo databases agree on which flag to draw.
* One of those call sites was `fetchIpInfoDirect()` — the **untunnelled** probe
  that fills the IP badge while the app is disconnected (`MainActivity.kt`, state
  `idle`).
* On that path it opened a second connection, in the clear, to `ip-api.com:80`,
  with the request line `GET /json/<the user's real public IP>?fields=status,countryCode`
  and the header `User-Agent: Aether/1.0`.
* Raw socket, so `cleartextTrafficPermitted="false"` never applied — the same
  exemption this report notes for F-5, still in force here.

**What actually leaks, stated precisely.** Not the IP address: the operator sees
that as the source address of every packet the device sends, so re-sending it
changes nothing. What leaks is a *plaintext request whose shape and User-Agent
identify this application*, repeated on every IP refresh, at the moment the user
is NOT protected. In this app's threat model the presence of the app is itself the
sensitive fact, which is exactly the reasoning F-5 used one paragraph earlier —
it simply was not carried through to this function. Severity is therefore Medium,
not Low: it is app-presence disclosure on the operator's network, not a
spoofable flag.

**Fix.** The decision is now a single pure function with two mandatory conditions:

```kotlin
internal fun shouldRefineCountry(providerHost: String, countryCode: String?, viaTunnel: Boolean): Boolean {
    if (!viaTunnel) return false                 // never on the operator's network
    if (providerHost == "ip-api.com") return false
    return countryCode.isNullOrBlank()           // only when there is no country at all
}
```

1. **Tunnelled only.** Through the tunnel the request leaves from the exit, so no
   one on the user's network sees it, and the address inside it is the exit's.
2. **Only when the country is unknown.** Harmonising a flag that is already known
   is cosmetic, and a cosmetic request does not get to exist.

`fetchIpInfoDirect()` no longer references `refineCountry` at all — verified in
the compiled bytecode (`javap -c`: the only callers are `fetchIpInfoViaSocks` and
the raced lambda, both tunnelled), not only in the source. The rule is pinned by
`app/src/test/java/studio/cluvex/aether/core/NetProbeGeoPolicyTest.kt` (7 cases,
including the full truth table), so a refactor that reintroduces the direct call
fails in CI instead of on a phone.

**The three User-Agent headers went with it.** `Aether/1.0` (`NetProbe`, the one
header that was operator-visible), `aether-ping` (`PingMonitor`) and
`Aether-Android/1.2.9` (`GeminiHttp` — also still claiming 1.2.9 inside a 1.3.0
build) are now `Mozilla/5.0`. The latter two ride inside TLS and were never
visible to the operator; they named the app to the endpoint for nothing.

**Reviewed in the same pass and deliberately left alone:**
`AetherVpnService.probeTunnelOnce()` completes a TLS handshake without hostname
verification. It sends no request and reads nothing out of the answer except
"bytes made a round trip", which is the whole point of the watchdog. Verifying
would make a captive portal or an interception proxy indistinguishable from a
wedged tunnel and drive a reconnect loop on precisely the networks where it must
not. A comment now states this at the call site. This does not contradict the
report's "every hand-rolled TLS socket verifies the hostname": the sockets that
carry data — `NetProbe.tlsWrap`, `GeminiHttp.tlsWrap`, `SmartAuto.tlsSniProbe` —
all verify.

**Scope of this fix.** Source-level and unit-tested; the app version is unchanged
(1.3.0 / versionCode 14 / PATCHLEVEL 1.3.0) because nothing user-facing changed.
No one has yet watched the traffic of a running build to confirm that no packet
reaches `ip-api.com` while disconnected — that is the one check a phone can make
and a sandbox cannot.

### F-6 — R8 off

`isMinifyEnabled = false`, `isShrinkResources = false`. This is an AGPL app whose
source is public, so obfuscation buys little against reverse engineering. What it
does buy is a smaller attack surface (dead code and unused library entry points
removed) and stripped debug metadata. Enabling it needs testing against
reflection in Compose and the Psiphon AAR, which is why it is Low, not Medium.

### F-7 — No automated dependency or secret scanning

The workflow builds Rust, Go and Gradle artefacts and never runs `cargo audit`,
an OSV/dependency check, or a secret scanner. The dependency set was reviewed by
hand for this audit and nothing with a known critical advisory was identified —
`ring 0.17.14`, `rustls 0.23.43`, `boring 4.22.0`, `tokio 1.53.1`,
`quiche 0.29.3`, `hyper 1.11.1`, AndroidX/Compose from BOM 2024.10.01 — but a
manual review in a sandbox without network access to the advisory databases is
not a substitute for a scan that runs on every push. **Treat this as the audit's
own limitation as much as a finding.** The Android dependency set is also roughly
a year behind current releases and should be moved forward on its own schedule.

### F-8 / F-9 — Supply chain

`app/libs/psiphontunnel-2.0.39.aar` (44 MB) is a binary in the repository. Its
SHA-256 is recorded in `SOURCE_MANIFEST.sha256`, which proves the tree is intact
but says nothing about where the binary came from: the hash is of the committed
file, not of an upstream release artefact. Recording the upstream release URL and
its published checksum next to it — or building it in CI, which already installs
Go — closes the gap. CI actions are pinned to mutable tags (`actions/checkout@v4`,
`softprops/action-gh-release@v2`); SHA pinning is the standard mitigation.

### F-10 — No pinning (accepted)

Trust is the system CA store, with user-added CAs excluded explicitly. Pinning
`generativelanguage.googleapis.com` in a client that ships from a GitHub release
and cannot be hot-fixed would convert a routine Google certificate rotation into
a permanently broken feature. Documented and accepted in
`network_security_config.xml`.

---

## 3. What was verified as correct

This section is the larger half of the audit, and it is evidence, not praise.

**Secrets (area 1).** Pattern scans for `AIza…`, `ghp_…`, `sk-…`,
`-----BEGIN … PRIVATE KEY-----` and `key|token|secret|password = "…"` over
`app/src`, `scripts` and `.github` return nothing. No API key, no server
credential, no symmetric key is embedded. The only private keys in the tree are
upstream quiche test fixtures under `native/aether/quiche/**/cert.key`, which are
not packaged into the APK. The user's Gemini key is read from `SecretStore` and
never from preferences (`GeminiStore.kt:81`), and the LAN credential is generated
by `SecureRandom` on first use rather than chosen by the user
(`LanGuard.randomPassword`, 16 chars).

**Crypto and TLS (area 2).** One primitive: AES-256-GCM
(`AES/GCM/NoPadding`, `setKeySize(256)`, `setRandomizedEncryptionRequired(true)`)
under a non-exportable `AndroidKeyStore` key — `KeyVault.kt:123-146` for files,
`SecretStore.kt:83-119` for preference values. No MD5, SHA-1, DES, RC4, Blowfish
or ECB anywhere in the app. Authentication comparison is constant-time by
construction: both sides are SHA-256'd first so `MessageDigest.isEqual` sees
equal-length inputs (`LanGuard.secretEquals`). **No custom `TrustManager`, no
permissive `HostnameVerifier`, no `setDefaultSSLSocketFactory` override exists in
the tree**, and each of the four hand-rolled TLS sockets verifies the certificate
name against the host explicitly (`NetProbe.kt:241`, `GeminiHttp.kt:280`,
`SmartAuto.kt:288`, `PsiphonSocksFront.kt:2321`) — `startHandshake()` alone would
not. `network_security_config.xml` declares `src="system"` only, so a root CA
installed through Settings — how mitmproxy, Burp and a hostile network's "install
this certificate" page work — cannot validate a chain for this app. No MitM path
was found.

**Leaks (area 3).** DNS: a hostname is never resolved on the device. `ATYP=DOMAIN`
is passed to the upstream proxy verbatim, and in Tor modes DNS is answered over
TCP *inside* Tor (`TorSocksFront.kt:50-58`), so `.onion` resolves and no query
reaches the local resolver. Non-DNS UDP in Tor modes is dropped rather than
leaked, QUIC included. IPv6: `::/0` is routed unconditionally in chained modes,
with the reason recorded in code — an uncaptured v6 flow would otherwise leave
with the phone's real address. Bypass: the app's own package is excluded from the
TUN for loop prevention, and every consequence of that is handled deliberately —
AI requests are dialled through the tunnel's own SOCKS proxy and the feature is
gated closed when there is no tunnel (`AiGate`), and `ShareBridge` is stopped
before the tunnel on teardown so sharing cannot outlive it. Split tunnelling in
INCLUDE mode never adds the app's own package. Sniffed SNI/Host values stay in an
in-memory, 300-second, 4096-entry cache (`DnsMap`) and are written to no log.

**Storage (area 4).** The diagnostics log is one AES-256-GCM record per flush
(`EncryptedLogFile`); if the keystore cannot produce a key the disk mirror is
switched **off** and said so once, rather than falling back to plaintext. The
engine's identity files — which contain the WireGuard private key — are sealed
while the tunnel is down and unsealed only for the engine's lifetime, with a
startup pass that closes the crash case (`IdentityVault`). A legacy plaintext log
is imported once and then shredded. What remains unencrypted is non-secret
configuration in DataStore (protocol, MTU, scan ranges, manual endpoints) — on a
hostile network the endpoint list is sensitive metadata, which is why backup and
device transfer are denied for every domain in both `backup_rules.xml` and
`data_extraction_rules.xml`, on top of `allowBackup="false"`.

**Permissions and OS configuration (area 5).** Five permissions: `INTERNET`,
`ACCESS_NETWORK_STATE`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`,
`POST_NOTIFICATIONS`. No `QUERY_ALL_PACKAGES` (split tunnelling uses a
launcher-intent `<queries>` filter), no `REQUEST_INSTALL_PACKAGES`, no storage or
location permission. No exported `ContentProvider`. `allowBackup="false"`,
`usesCleartextTraffic="false"`, `android:debuggable` never set. Of the exported
components, `MainActivity` is the launcher and `AetherTileService` is guarded by
`BIND_QUICK_SETTINGS_TILE`; the VPN service is `exported="false"` behind
`BIND_VPN_SERVICE`, and the widget receiver is `exported="false"` and driven by an
explicit component. Every `PendingIntent` in the app is `FLAG_IMMUTABLE`. No
WebView, no `loadUrl`, no `addJavascriptInterface` anywhere.

**Logging (area 6).** Three `android.util.Log` calls exist in the entire app; one
is the engine's stdout mirror and is gated on `BuildConfig.DEBUG`
(`AetherProcess.kt:106`), the other two report whether the native tunnel library
loaded. No `println`, no `System.out`. Everything else goes to the in-app log,
which is redacted and encrypted at rest. The AI path sends at most a redacted
digest (220 lines / 12 000 characters) and drops any line the redactor fails on;
that behaviour is covered by `AiRedactionTest` (12 cases).

**AI blast radius.** The assistant's ability to change settings is bounded by an
allow-list (`AiPatch.WRITABLE`) rather than by prompt wording, which is the right
construction: the model's input includes log text, so it is an untrusted source.
Bridge settings, credentials and the sharing switch are not writable, and a
refused change is surfaced to the user instead of being swallowed. Tests:
`AiModelPolicyTest`, `AiRedactionTest`, `LanGuardTest`, `TorSocksWireTest`,
`TorBootstrapTest` — 67 cases, all passing in this build.

---

## 4. Recommendations, in the order worth doing them

1. Route `::/0` unconditionally in `ensureLockdownTun` (F-1).
2. Default `killSwitch` to on (F-2).
3. Add `FLAG_SECURE` to the settings, share and diagnostics surfaces, and
   `EXTRA_IS_SENSITIVE` to every clipboard copy of a secret (F-3, F-4).
4. Add `cargo audit` and a Gradle dependency check to CI; pin actions by SHA
   (F-7, F-9).
5. Replace the cleartext geo fallbacks with `1.1.1.1:443` (F-5), and gate the
   ip-api refinement on "tunnelled" + "country unknown" (F-5b).
6. Record the upstream URL and published checksum of the Psiphon AAR, or build it
   in CI (F-8).
7. Turn R8 on and test Compose/Psiphon reflection paths (F-6).

None of these is a change to the tunnel's data plane, and none of them affects
installability over a previous version.

---

## 5. Scope and limitations

* App signing, keystore handling and update compatibility are **out of scope** by
  product decision (see the note at the top).
* The Rust engine was reviewed for TLS-verification bypasses (none outside
  upstream test and example code) and for its dependency inventory. A full review
  of the vendored engine and of quiche is not in this pass.
* No dynamic analysis, no instrumented device test, no traffic capture: this is a
  source-level audit. The runtime claims in §3 are read from code, not observed on
  a phone.
* `cargo audit` and Gradle dependency verification could not be executed (no
  network access to advisory databases in the audit environment). See F-7.

---

## 6. Remediation applied in this build

Seven of the ten findings were fixed in the same 1.3.0 tree, after the audit and
before the release went out. Everything below compiles, the unit suite passes
(67 tests, 0 failures), and the version is unchanged (`versionCode 14`,
`versionName 1.3.0`).

**Read this section as source-level, exactly like the rest of the report.** No
change here was observed on a phone: nobody has yet confirmed that the recents
thumbnail is blank, that a clipboard preview hides the LAN password on a real
Android 13 device, or that `1.1.1.1:443` verifies on every OEM TLS stack. Those
are the three things to check first on a device.

| ID | Fix | Where |
| --- | --- | --- |
| F-1 | `ensureLockdownTun` now claims the v6 address and routes `::/0` unconditionally | `AetherVpnService.kt` |
| F-2 | `killSwitch` defaults to `true`, in the data class **and** in the store's fallback | `Profile.kt`, `ProfileStore.kt` |
| F-3 | `SecureSurface()` holds `FLAG_SECURE` while a secret-bearing surface is composed; `CrashReportActivity` sets it on its window | new `ui/components/PrivacyGuard.kt`; wired in `SharePanel.kt`, `DiagnosticsPanel.kt`, `AiPages.kt`, `SettingsScreen.kt`, `CrashReportActivity.kt` |
| F-4 | `copySensitive()` sets `EXTRA_IS_SENSITIVE` on the clip; used for the proxy credential, the verbatim log and the crash dump | `PrivacyGuard.kt` + the three call sites |
| F-5 | Both cleartext geo providers dropped; the fallback is `1.1.1.1:443` over TLS | `NetProbe.kt` |
| F-5b | `shouldRefineCountry()` gates the ip-api refinement on "through the tunnel" AND "no country known"; `fetchIpInfoDirect` no longer refines at all; the three app-naming User-Agents are gone | `NetProbe.kt`, `PingMonitor.kt`, `GeminiHttp.kt`, new `NetProbeGeoPolicyTest.kt` |
| F-7 | `cargo audit` step in CI (non-blocking) + Dependabot for the gradle set: one entry, monthly, all bumps grouped into a single PR | `.github/workflows/build.yml`, new `.github/dependabot.yml` |
| F-8 | Provenance record for the committed AAR: size, SHA-256, upstream project, and what the hash does *not* prove | new `app/libs/PROVENANCE.md` |

### Details worth knowing

**F-2 does not override a user's choice.** `ProfileStore` writes every key when
anything changes, so a user who deliberately switched the kill switch off has
`false` on disk and keeps it. The new default applies where the key was never
written: a fresh install, and an existing install whose owner never touched the
setting. The latter *is* a behaviour change on update, in the safe direction.

**`SecureSurface` is ref-counted.** Several secure surfaces can be alive at once
(the settings host under an AI page, a dialog over the share panel). A naive
`clearFlags` on dispose would strip protection from a screen that is still
showing, so the flag is set on the first claim and cleared on the last release.

**Why `1.1.1.1` and not a hostname.** The IP literal keeps DNS out of the probe
path, which is the point of `GeoProvider.hostIsDomain = false` — a poisoned
resolver cannot redirect it. Verification still holds: the certificate carries
`1.1.1.1` as an iPAddress SAN and `tlsWrap` checks it through the platform
verifier. If some OEM verifier refuses an IP literal, this is a fallback provider
only, so the failure mode is "no result", never a wrong exit IP.

**The `cargo audit` step is deliberately non-blocking.** A fresh RUSTSEC advisory
in a transitive dependency of the engine is a reason to look, not a reason to stop
shipping a circumvention tool — and since `sync-core.sh` re-syncs the engine from
upstream on every run, the fix for most findings does not live in this repository
at all. The finding now lands in the job summary on every push instead of waiting
for the next manual audit.

**Not fixed on the Gradle side.** The OWASP dependency-check plugin now requires an
NVD API key, and adding a scanner binary downloaded without a verifiable checksum
would be a supply-chain hole of its own. Dependabot covers the Android dependency
set server-side instead.

**Dependabot was cut back after the first push, and that is part of the finding.**
The config as first written listed three ecosystems on a weekly schedule with five
PRs allowed each. GitHub starts one `Dependabot Updates` run per ecosystem entry,
so the file alone produced a burst of runs in the Actions tab, and merging any of
the resulting PRs would have triggered a full signed release from the push to
`main`. Surveillance that a single maintainer cannot keep up with is not
surveillance, so the config is now one entry (gradle), monthly, grouped into one
PR, and the workflow skips dependency commits. Two structural weaknesses in the
workflow surfaced while fixing this and were closed with it: there was no
`concurrency` group, so two pushes in quick succession could have two runs signing,
pushing commits back to the branch and publishing to the same release tag; and
nothing distinguished a dependency bump from a release-worthy change.

### Still open

* **F-6 (R8).** Off. A reflection break in Compose or in the Psiphon AAR surfaces
  on a device, not in a unit test; for this app a broken release is worse than an
  unminified one. Turning it on needs an instrumented pass over the AI pages, the
  Psiphon path and the share bridge.
* **F-9 (SHA-pinned actions).** Open, and now fully manual. The commit SHAs for
  `actions/checkout@v4` and the rest could not be looked up in the audit
  environment, and inventing them is not an option. The `github-actions` ecosystem
  was also removed from `dependabot.yml` (see above), so nothing keeps the tags
  moving automatically any more: pin the SHAs by hand from a machine with network
  access, and re-check them when an action is deliberately upgraded.
* **F-8, the real fix.** The provenance file records what is committed; it does not
  prove where the binary came from. Either compare against the upstream published
  artefact or build the AAR in CI, where Go is already installed.
