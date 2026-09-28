<p align="center">
  <img src="docs/ads.png" alt="Aether — Freedom, in one tap" width="100%">
</p>

<h1 align="center">Aether Mobile</h1>

<p align="center">
  A stunning, dark <b>Material You</b> Android client for the Aether engine — with the
  tunnelling core built <b>inside the app</b>, so you no longer need v2rayNG.
</p>

<p align="center">
  <a href="README.md">🇬🇧 English</a> · <a href="README.fa.md">🇮🇷 فارسی</a>
</p>

<p align="center">
  <img alt="version" src="https://img.shields.io/badge/version-1.4.0-8B5CF6?style=flat-square">
  <img alt="engine core" src="https://img.shields.io/badge/engine%20core-2.1.0-22D3EE?style=flat-square">
  <img alt="min Android" src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=flat-square">
  <img alt="license" src="https://img.shields.io/badge/license-AGPL--3.0-F59E0B?style=flat-square">
</p>

---

## What's new in v1.4.0

A maintenance release built entirely from the 1.3.0 field reports, plus one new
automatic mode. Full detail in [`CHANGELOG.md`](CHANGELOG.md), file-by-file in
[`PATCH_NOTES_1.4.0.md`](PATCH_NOTES_1.4.0.md).

### The VPN share pages, redesigned (Persian and English)

The pages a shared device sees, `http://aether.check/` and the setup page at
`http://<phone>:10811/`, were a wall of mixed English and Persian that fell apart
on a phone. They are rebuilt:

- **One dark navy layout** that reads well on a phone, a laptop and a TV browser.
- **A language switch at the top:** فارسی (default) or English. The choice is
  remembered in that browser.
- **Persian is right-to-left throughout**, in Vazirmatn / Vazir when the device has
  it and in the system's own Persian font when it does not. Every English word,
  address, port and file name inside a Persian sentence stays left-to-right as a
  block, so nothing gets scrambled.
- **Plain, step-by-step Persian:** copy buttons for the PAC / HTTP / SOCKS5
  addresses, setup steps for Windows, macOS, Android, iPhone, Firefox and TVs, and
  a guide for **both share modes, home Wi-Fi and phone hotspot**, with the mode the
  phone is in opened and marked.
- The live WebRTC test and the one-click WebRTC locks work exactly as before, now
  explained in both languages.
- The pages load nothing from the internet and never show the share password.

### 🔒 Security audit 1.4.0: **92 / 100**

A fresh 0-100 audit of the whole app, with the LAN share scored as its own area
for the first time. Full report:
[`docs/SECURITY_AUDIT_1.4.0.md`](docs/SECURITY_AUDIT_1.4.0.md).
Version **1.4.0** (version code 16), engine **2.1.0**. **Signing is unchanged**, so
1.4.0 installs straight over 1.3.0, no uninstall.

| # | Area | Weight | Score | Weighted |
| --- | --- | ---: | ---: | ---: |
| 1 | Secrets & key management | 14 | 95 | 13.30 |
| 2 | Cryptography, TLS & MitM resistance | 13 | 95 | 12.35 |
| 3 | Data-leak risk (DNS, IPv6, tunnel bypass) | 17 | 93 | 15.81 |
| 4 | LAN sharing surface (proxy, pages, WebRTC) | 10 | 86 | 8.60 |
| 5 | Local storage at rest | 12 | 94 | 11.28 |
| 6 | Permissions & OS configuration | 7 | 98 | 6.86 |
| 7 | Logging & diagnostics | 7 | 96 | 6.72 |
| 8 | Code quality & network configuration | 8 | 84 | 6.72 |
| 9 | On-device exposure (screen, clipboard) | 6 | 92 | 5.52 |
| 10 | Supply chain & build integrity | 6 | 80 | 4.80 |
| | **Total** | **100** | | **91.96 ≈ 92** |

| ID | Severity | Finding | Status |
| --- | --- | --- | --- |
| SA-1 | Medium | LAN sharing has no password by default, so any device on the same Wi-Fi or hotspot subnet can use the tunnel | Open by product decision; the setup page now says so and tells the user when to turn it on |
| SA-2 | Low | Share pages had no Content-Security-Policy or referrer policy, the phone address was not HTML-escaped, and the WebRTC result was written with raw candidate data | **Fixed in 1.4.0** |
| SA-3 | Low | Share credentials travel in clear on the LAN (HTTP Basic / SOCKS5 RFC 1929), inherent to both protocols | Accepted, same-subnet only |
| SA-4 | Low | On a hotspot, guest WebRTC goes through Android's tethering NAT, which an unrooted app cannot filter | Mitigated (live test + one-click locks), accepted |
| SA-5 | Low | R8 / minification is off for release builds | Open (deliberate, unchanged) |
| SA-6 | Low | CI actions are pinned by tag; `dtolnay/rust-toolchain@stable` follows a moving branch | Open |
| SA-7 | Info | No certificate pinning; trust is system CAs only (user CAs refused) | Accepted |
| SA-8 | Info | App signing is out of scope: 1.4.0 keeps the exact signing identity of 1.3.0 so it installs over the current app without an uninstall | Not scored |

<details>
<summary>🇮🇷 همین ممیزی به فارسی</summary>

<div dir="rtl" align="right">

<h3 dir="rtl" align="right">‏🔒 ممیزی امنیتی <span dir="ltr">1.4.0</span>: <strong>۹۲ از ۱۰۰</strong></h3>

<p dir="rtl" align="right">‏یه ممیزی تازهٔ صفر تا صد از کل برنامه گرفته شد و این بار بخش اشتراک روی شبکهٔ محلی هم جدا امتیاز گرفت. گزارش کامل: <span dir="ltr"><a href="docs/SECURITY_AUDIT_1.4.0.md">docs/SECURITY_AUDIT_1.4.0.md</a></span>. نسخهٔ برنامه <span dir="ltr">1.4.0</span>، کد نسخه <span dir="ltr">16</span> و هستهٔ <span dir="ltr">2.1.0</span>. <strong>امضا دست نخورده</strong>، پس مستقیم روی ۱.۳.۰ نصب می‌شه و لازم نیست حذفش کنی.</p>

<table dir="rtl" align="right">
<thead><tr><th align="right">#</th><th align="right">حوزه</th><th align="right">وزن</th><th align="right">امتیاز</th><th align="right">امتیاز وزنی</th></tr></thead>
<tbody>
<tr><td align="right">۱</td><td align="right">اسرار و مدیریت کلید</td><td align="right">۱۴</td><td align="right">۹۵</td><td align="right">۱۳٫۳۰</td></tr>
<tr><td align="right">۲</td><td align="right">رمزنگاری، <span dir="ltr">TLS</span> و مقاومت در برابر <span dir="ltr">MitM</span></td><td align="right">۱۳</td><td align="right">۹۵</td><td align="right">۱۲٫۳۵</td></tr>
<tr><td align="right">۳</td><td align="right">خطر نشت داده (<span dir="ltr">DNS</span>، <span dir="ltr">IPv6</span>، دور زدن تونل)</td><td align="right">۱۷</td><td align="right">۹۳</td><td align="right">۱۵٫۸۱</td></tr>
<tr><td align="right">۴</td><td align="right">سطح اشتراک روی شبکهٔ محلی (پراکسی، صفحه‌ها، <span dir="ltr">WebRTC</span>)</td><td align="right">۱۰</td><td align="right">۸۶</td><td align="right">۸٫۶۰</td></tr>
<tr><td align="right">۵</td><td align="right">ذخیره‌سازی محلی</td><td align="right">۱۲</td><td align="right">۹۴</td><td align="right">۱۱٫۲۸</td></tr>
<tr><td align="right">۶</td><td align="right">مجوزها و پیکربندی سیستم</td><td align="right">۷</td><td align="right">۹۸</td><td align="right">۶٫۸۶</td></tr>
<tr><td align="right">۷</td><td align="right">لاگ و عیب‌یابی</td><td align="right">۷</td><td align="right">۹۶</td><td align="right">۶٫۷۲</td></tr>
<tr><td align="right">۸</td><td align="right">کیفیت کد و پیکربندی شبکه</td><td align="right">۸</td><td align="right">۸۴</td><td align="right">۶٫۷۲</td></tr>
<tr><td align="right">۹</td><td align="right">افشا روی خود گوشی (صفحه، کلیپ‌بورد)</td><td align="right">۶</td><td align="right">۹۲</td><td align="right">۵٫۵۲</td></tr>
<tr><td align="right">۱۰</td><td align="right">زنجیرهٔ تأمین و سلامت بیلد</td><td align="right">۶</td><td align="right">۸۰</td><td align="right">۴٫۸۰</td></tr>
<tr><td align="right"></td><td align="right"><strong>جمع</strong></td><td align="right"><strong>۱۰۰</strong></td><td align="right"></td><td align="right"><strong><span dir="ltr">۹۱٫۹۶</span> ≈ ۹۲</strong></td></tr>
</tbody>
</table>

<p dir="rtl" align="right">&nbsp;</p>

<table dir="rtl" align="right">
<thead><tr><th align="right">شناسه</th><th align="right">شدت</th><th align="right">یافته</th><th align="right">وضعیت</th></tr></thead>
<tbody>
<tr><td align="right"><span dir="ltr">SA-1</span></td><td align="right">متوسط</td><td align="right">اشتراک روی شبکهٔ محلی به‌طور پیش‌فرض رمز نداره؛ هر دستگاهی که تو همون وای‌فای یا هات‌اسپات باشه می‌تونه از تونل استفاده کنه</td><td align="right">باز، طبق تصمیم محصول؛ صفحهٔ راه‌اندازی حالا اینو صریح می‌گه و می‌گه کِی روشنش کنی</td></tr>
<tr><td align="right"><span dir="ltr">SA-2</span></td><td align="right">کم</td><td align="right">صفحه‌های اشتراک <span dir="ltr">Content-Security-Policy</span> و سیاست <span dir="ltr">Referrer</span> نداشتن، آدرس گوشی escape نمی‌شد و نتیجهٔ <span dir="ltr">WebRTC</span> با دادهٔ خام نوشته می‌شد</td><td align="right"><strong>در <span dir="ltr">1.4.0</span> رفع شد</strong></td></tr>
<tr><td align="right"><span dir="ltr">SA-3</span></td><td align="right">کم</td><td align="right">نام کاربری و رمز اشتراک روی شبکهٔ محلی بی‌رمز جابه‌جا می‌شه (<span dir="ltr">HTTP Basic</span> و <span dir="ltr">SOCKS5 RFC 1929</span>)؛ ذات خود این پروتکل‌هاست</td><td align="right">پذیرفته‌شده، فقط داخل همون زیرشبکه</td></tr>
<tr><td align="right"><span dir="ltr">SA-4</span></td><td align="right">کم</td><td align="right">تو حالت هات‌اسپات، <span dir="ltr">WebRTC</span> دستگاه مهمان از <span dir="ltr">NAT</span> خود اندروید رد می‌شه و برنامه بدون روت نمی‌تونه فیلترش کنه</td><td align="right">کاهش‌یافته (تست زنده و قفل یک‌کلیکی)، پذیرفته‌شده</td></tr>
<tr><td align="right"><span dir="ltr">SA-5</span></td><td align="right">کم</td><td align="right"><span dir="ltr">R8</span> و کوچک‌سازی کد تو بیلد نهایی خاموشه</td><td align="right">باز (عمدی، بدون تغییر)</td></tr>
<tr><td align="right"><span dir="ltr">SA-6</span></td><td align="right">کم</td><td align="right">اکشن‌های <span dir="ltr">CI</span> با تگ پین شدن و <span dir="ltr">dtolnay/rust-toolchain@stable</span> یه شاخهٔ متحرکه</td><td align="right">باز</td></tr>
<tr><td align="right"><span dir="ltr">SA-7</span></td><td align="right">اطلاعاتی</td><td align="right">پین گواهی نداره؛ فقط <span dir="ltr">CA</span>های سیستم قبوله (<span dir="ltr">CA</span> کاربر رد می‌شه)</td><td align="right">پذیرفته‌شده</td></tr>
<tr><td align="right"><span dir="ltr">SA-8</span></td><td align="right">اطلاعاتی</td><td align="right">امضای برنامه خارج از دامنهٔ ممیزیه: <span dir="ltr">1.4.0</span> دقیقاً همون امضای ۱.۳.۰ رو نگه می‌داره تا بدون حذف، روی نسخهٔ فعلی نصب بشه</td><td align="right">امتیازدهی نشده</td></tr>
</tbody>
</table>

</div>

</details>

### Smart got faster, in the same button

**Smart** used to try one strategy at a time until one worked. It still does that on
the chained backends — but on plain **Aether** it now does two more things:

- **It tries two routes at the same time** and keeps whichever one actually carries
  traffic. Trying them one after another means a bad first guess costs a full
  minute; raced, it costs nothing, because the other route is already running.
- **It remembers what worked on this network.** Next time you connect on the same
  Wi-Fi or the same SIM, that route is tried first, on its own — so the second
  connect is usually a few seconds and no second engine is started at all.

Worst case falls from about five and a half minutes to about three, and when nothing
on the network works you find out in one minute instead of five.

**Nothing to switch on.** Keep the protocol on **Smart**. The row under the selector
tells you which of the two this profile gets: the race needs the plain Aether
backend with automatic endpoint selection, so `Aether → Psiphon`, `Aether → Tor`, a
hand-pinned endpoint or Zero Trust with the e-mail code get the one-at-a-time
ladder, exactly as before.

| | What it does |
| --- | --- |
| **Smart** | Measures your network, then races two routes on plain Aether (remembering what worked here) or tries them one at a time on a chained backend |
| **A protocol by hand** (WireGuard, MASQUE, WARP×2, MASQUE×2) | Exactly what you chose, nothing else |

**What it costs.** While it is searching on plain Aether, two engines run instead of
one, so that part of the connect uses more battery and more data. Once connected,
nothing is different.

**What it cannot do.** Open a network that is closed. Where the block is
Cloudflare's WARP registration being unreachable, racing only tells you faster.

### Fixed in 1.4.0

- **The app no longer takes about ten seconds to open.** It was decrypting the
  whole encrypted diagnostics log on the main thread before the first frame, and
  that log is thousands of separately sealed records, each one a trip into the
  hardware keystore — then it kept only the newest 800 lines. Restore now runs in
  the background and only the retained tail is decrypted. This is also what was
  behind the `ForegroundServiceDidNotStartInTimeException` crashes: a main thread
  parked in the keystore cannot answer `startForegroundService()` in time.
- **The widget no longer sticks on "Disconnecting…".** Two causes: the final step
  of a disconnect never repainted it, and in a cold receiver process it read a
  connection state that only exists inside the running app. It is also 1×1 now
  instead of 3×1, resizable, and the power icon takes the colour of the state. The
  notification's action button says "Disconnect" rather than being labelled with a
  status.
- **Private Space works.** Every loopback port was a `const`, so a second instance
  in another Android profile lost the bind and failed before any tunnel. New
  `core/PortLease.kt` resolves them per session — preferred port when free, next
  free one otherwise — and the app now passes `--bind` / `--tor-bind`, which the
  engine always accepted and the app never used.
- **A working endpoint is no longer thrown away on every reconnect.**
  `fastEndpointOnly` defaulted to on with budgets calibrated for 100–150 ms edges;
  the field logs contain nothing under 727 ms, so the cache was discarded every
  time and each reconnect paid for a full scan. Default is now off, and the budgets
  it sends when switched on match real networks.
- **Zero Trust: the e-mail code is finally asked for.** The engine prints a
  machine-readable prompt on stdout and waits on stdin; the app had never looked
  for it. A dialog now appears mid-connect. Enrolment tokens are checked as they
  are pasted. Still authentication *during* a connect, not before it — the
  reasoning is in [`docs/ZERO_TRUST.md`](docs/ZERO_TRUST.md).
- **Android TV**, **x86_64 builds**, **a typed MTU** (1280–9000), **correct
  operator detection on dual-SIM phones**, **a pasted ECHConfigList**, and a
  prompt when Android is still allowed to suspend the app.
- **Two build fixes:** the Tor feature check no longer trips `pipefail` on a good
  binary, and the project's only `lint` error — present in 1.3.0 as shipped — is
  gone.
- **Version:** app <span dir="ltr">1.4.0</span>, version code <span dir="ltr">16</span>,
  engine core <span dir="ltr">2.1.0</span>. Installs straight over
  1.3.0 — the signing configuration has not changed.

> **Verified, not field-tested.** 1.4.0 compiles clean, 88 unit tests pass and
> `lint` reports no errors, but it has not been run on a handset. The startup fix,
> the widget fix, the Private Space fix and the Zero Trust handshake are the four
> claims that need a device to confirm.

## Previously in v1.2.9

- **Two real privacy leaks were found and closed while fixing that crash.** IPv4-mapped IPv6 addresses (`::ffff:203.0.113.9`) were only half-masked, leaving three octets of a public address in the digest, and Psiphon's JSON identifiers (`"sessionId":"…"`) were not recognised as identifiers at all. Both are masked now, and the redactor's test suite grew from 8 cases to 12.
- **Engine (core) upgraded to v1.9.0** (previous: v1.8.0), with this app's own engine patches rebased onto the new sources rather than overwritten:
  - **You can name the two hops of WARP-in-WARP (gool) yourself.** New engine options `--wiw-outer` / `--wiw-inner` / `--wiw-peers` (and `--wiw-scan` to go back to hunting for both). Naming one hop lets the scan find the other, the port has to be written out, and the two hops must be different edges. A malformed address is now reported before an account is provisioned instead of silently falling back to a scan.
  - **MASQUE over HTTP/2 is no longer capped by its own carrier.** The HTTP/2 flow-control windows were the RFC minimum of 64 KB, which limits any download over that transport to roughly 500 KB/s on a 130 ms path however fast the line really is. The windows now follow the device tier, DATA frames may be 64 KB, outbound packets already queued behind one another are sent in one frame instead of one frame each, sending moved to a task of its own (so a busy upload can no longer stall the download beside it), and the HTTP/2 tunnel gets a full 1500-byte inner MTU instead of the 1280 that only QUIC needs.
  - **Engine buffers can be tuned per device** without a rebuild (`AETHER_NETSTACK_TCP_RX`, `AETHER_NETSTACK_TCP_TX`, `AETHER_MASQUE_MTU`), and the engine's `help` output now documents every option together with the environment variable that sets it.
- **Everything 1.2.8 fixed stays exactly as it was.** This was a condition of the upgrade, not an afterthought: core 1.9.0 independently re-sized the same data-plane buffers 1.2.8 spent five rounds getting right, and those upstream numbers were **not** taken. The netstack keeps its CUBIC congestion control, its bounded uplink send buffer and its bandwidth-delay-product receive window, the datagram sockets keep the split receive/send sizing from r6, and the TCP stack stays pinned to the version the app's netstack is written against. Download speed and connect behaviour on every protocol are the 1.2.8 ones.
- **The next automatic core upgrade is safer than this one was.** Pristine upstream copies of *all ten* app-patched engine files are now cached as the merge baseline (1.2.8 cached two), so CI's three-way rebase can never mistake an app patch for an upstream deletion.
- **Version:** app <span dir="ltr">1.2.9</span>, version code <span dir="ltr">13</span>, engine core <span dir="ltr">1.9.0</span>. Installs straight over 1.2.8 from the same repository - the signing configuration is unchanged.

### 🔒 Security audit 1.2.9 — **79 / 100**

A full mobile-app security audit was run over the whole shipped tree (Kotlin app, Gradle + signing config, manifest, the Rust engine, the embedded Psiphon library and CI). Full report: [`docs/SECURITY_AUDIT_1.2.9.md`](docs/SECURITY_AUDIT_1.2.9.md).

| # | Area | Weight | Score |
| --- | --- | --- | --- |
| 1 | Secrets & key management | 20 | 55 |
| 2 | Cryptography & protocols (TLS, MitM) | 15 | 88 |
| 3 | Data-leak risk (DNS, IPv6, bypass) | 20 | 90 |
| 4 | Local storage | 15 | 72 |
| 5 | Permissions & OS configuration | 10 | 96 |
| 6 | Logging | 10 | 85 |
| 7 | Code quality & network config | 10 | 80 |
| | **Weighted total** | **100** | **79** |

**What is good:** no API key, token or private key is hardcoded anywhere in the app; the user's Gemini key and the Cloudflare Access secrets are sealed with AES-256-GCM under a non-exportable Android Keystore key; there is **no custom `TrustManager` and no permissive hostname verifier** anywhere, and every hand-rolled TLS socket verifies the certificate name explicitly, so no MitM path was found; no weak or obsolete crypto (no MD5/SHA-1/DES/RC4/ECB); DNS is resolved by the exit, not by the device, and IPv6 is routed into the tunnel (unconditionally in chained mode) so there is no DNS or IPv6 leak path; cleartext HTTP is denied app-wide; backups are disabled twice over; only four permissions are requested, with no `QUERY_ALL_PACKAGES`, no `REQUEST_INSTALL_PACKAGES` and no exported content provider; `android:debuggable` is never set and `allowBackup` is `false`; and the app contains **no analytics, no crash-reporting SDK and no tracking library** of any kind.

**What costs points:** the release signing key is still committed to the repository with its password in the build file (**critical**, and the one issue that accounts for almost the whole gap - it needs a key rotation, not a code change; the 1.2.9 build already makes that key opt-in and warns loudly when it is used); the diagnostics log and the engine's identity file (which holds the WireGuard private key) are plaintext on app-private storage; LAN sharing, when the user switches it on, is an unauthenticated proxy for everyone on the Wi-Fi; there is no certificate pinning; and R8/minification is off. With the signing key rotated out and R8 enabled, the same tree scores ≈ 88.

### 🤖 Your logs and your data are not sent to the AI

Stated explicitly, because it is the question that matters: **the app does not send your sensitive information to any AI.**

- The AI features only exist if **you** paste **your own** Gemini API key and switch them on. With the feature off - the default - the app makes **no** request to any AI service whatsoever.
- What is sent is a **redacted digest** of the diagnostics log (at most 220 lines / 12,000 characters), over TLS, through the tunnel's own local proxy - never your raw log.
- Removed before anything leaves the phone: your API key and any key/token/secret/password/authorization value (in plain **and** JSON form), the WARP `device=` enrolment handle, installation and session identifiers (including Psiphon's `"sessionId"`) and every UUID, public IPv4 addresses (masked to `188.114.x.x`), public IPv6 addresses (masked to `2606:4700:x:x` - your assigned WARP IPv6 is globally routable and stable, so a full address *is* you), and IPv4-mapped IPv6 addresses.
- Kept on purpose, because they are plumbing and not identity: `127.0.0.1:1819`, private/loopback addresses, timestamps, protocol and error text, the build stamp.
- **Never sent at all:** your browsing history, DNS queries, visited domains, traffic contents or packet payloads, contacts, files, device identifiers or location. There is no telemetry in this app.
- If the redactor ever fails on a line, that line is **dropped** - it is never sent unredacted. That is enforced in code and covered by unit tests.

---

## What's new in v1.2.8

Users reported several connection problems, especially when using chained mode (`Aether → Psiphon`). These problems have now been fixed:

- In chained mode, sometimes only Telegram and Instagram opened. Browsers and many other apps did not work.
- Gemini, ChatGPT and similar AI apps sometimes said there was no internet connection.
- CapCut could fail to open or its effects would not load. It now works in chained mode, including loading effects.
- TikTok could fail to open with Aether alone. Chained mode is recommended for TikTok and CapCut.
- Sometimes the app showed that the connection was active, but browsing and live content did not work properly.
- During long uploads or live sessions, the connection could become very slow or stop passing data. It can now handle this better and recover automatically.
- Ping and connection status were not always reliable. The app now checks the connection more accurately and shows a more trustworthy status.

These fixes apply to plain Aether and, where relevant, to `Aether → Psiphon`.

---

## What's new in v1.2.5

- **Engine upgraded:** bundled core is now <span dir="ltr">1.6.0</span>.
- **Build compatibility fixed:** the manual WireGuard range parser is app-owned and no longer depends on a removed internal helper, so the native engine compiles cleanly.
- **Signing preserved:** version code is **9** and the existing signing configuration remains unchanged for in-place updates from the same repository.
- **UI compatibility preserved:** existing protocol, proxy, routing, diagnostics, and advanced settings remain available.

**Version:** app <span dir="ltr">1.2.5</span>, version code <span dir="ltr">9</span>.

## What's new in v1.2.4

- **Kill Switch and Strict Kill Switch**: on an unexpected drop, a blocking blackhole TUN stays up so no traffic leaks outside the VPN; Strict mode keeps blocking even after a manual disconnect until you lift it yourself.
- **IPv6 Leak Protection**: new toggle, on by default; the IPv6 default route is kept inside the tunnel.
- **Smart Reconnect with a retry limit**: the number of automatic reconnect attempts is configurable (3 to 20) before an error is reported.
- **Per-app internet blocking**: a newly added userspace filter bridge resolves each flow's owning app and drops blocked apps' traffic; it replaces the default hev forwarder only while this feature is enabled, so the default path stays untouched.
- **Advanced engine settings**: Fragment Size/Delay ranges, No Data Check, TLS Groups, Validate/Reconnect Secs, No Profile Retry and the core log level, all validated and mapped only to flags the bundled native core actually supports.
- **New UI sections**: "Security & stability" and "Advanced engine settings" in the Advanced panel, fully localized (English + فارسی) and persisted in DataStore and the profile codec.
- **"Works 1-2 minutes, then no site opens" fixed**: a connection watchdog probes the tunnel end-to-end every 30 seconds and restarts the engine on sustained failure, and the tunnel's idle timeouts were raised (TCP 60 s to 5 min, UDP to 120 s).
- **Periodic drop-outs fixed at the root**: the watchdog probe is now multi-attempt and multi-target and restarts the engine only after three consecutive failed checks, so brief, self-healing network stalls no longer kill a healthy session and force a long endpoint rescan.
- **Desktop-parity info row**: Protocol, Endpoint and live Latency now sit directly under the IP badge (ported from the Windows edition); the old standalone ping badge was removed.
- **Security**: a fresh 0-100 security audit was performed and scored **92/100** (full report: [`docs/SECURITY_AUDIT_1.2.4.md`](docs/SECURITY_AUDIT_1.2.4.md)):
  | Area | Result |
  | --- | --- |
  | 1. Keys & secrets | Zero Trust secrets sealed in the Android Keystore (AES-GCM); no hardcoding; passed to the engine only via env |
  | 2. Cryptography & protocols | WireGuard and MASQUE/QUIC with TLS 1.3, ECH and ClientHello fragmentation; no custom crypto |
  | 3. Data leaks | Full IPv4/IPv6 routing, in-tunnel DNS, Kill Switch; the only public egress is the IP badge's geolocation probe |
  | 4. Local storage | Private DataStore, `allowBackup=false`, no exported Provider |
  | 5. Permissions & manifest | Minimal permissions, no `QUERY_ALL_PACKAGES` or `debuggable` |
  | 6. Logs | In-memory only; engine output reaches Logcat only in debug builds |
  | 7. Code quality & network | Cleartext denied app-wide, proxies bound to 127.0.0.1, input validation; watchdog self-DoS fixed |
- **Version**: app 1.2.4 (version code 8).

## What's new in v1.2.3

This release upgrades the bundled engine to **core v1.5.0**, exposes everything
new that core added to the Android UI, brings the About panel to parity with the
Windows edition, and ships a fresh security audit.

### 🚀 Engine upgraded to core v1.5.0 — and the app patches were rebased properly

- **The core is now v1.5.0.** The previous release pinned `CORE_VERSION` at `1.4`, but the sources actually vendored were upstream **v1.3.0** — so the automatic merge-base logic had nothing valid to compare against and silently dropped the app's own engine patches. The baseline was identified, the patches were re-applied with a genuine **three-way merge**, and `native/aether/.upstream-baseline/` was repaired so the next automatic upgrade has a real merge base.
- **Upstream refactors absorbed, app features kept.** Core v1.5.0 turned the endpoint-range constants into functions (`masque_cidrs_v4()`, `wg_prefixes_v4()`, `wg_seeds_v4()`) with Zero-Trust-aware ordering. Both merge conflicts were resolved by **adopting upstream's new ordering** while keeping the app's manual-range override intact. The merged files now differ from pure upstream by nothing but the additive patch.

### 🆕 Everything new in core v1.5.0 is now in the UI

Core v1.5.0 was not just a version bump — it added whole new modules
(`zerotrust.rs`, `routing.rs`, `apifront.rs`, `sysprofile.rs`). The
user-facing options are all wired into **Advanced settings** now:

- **Zero Trust (WARP for organizations).** Join a Cloudflare Zero Trust organization instead of consumer WARP. Four enrolment methods are offered: off, **service token** (client id + secret), **e-mail one-time code**, and a **pre-obtained enrolment token**. The organization **Gateway** proxy is a separate opt-in toggle.
- **Routing rules.** Two new lists decide where traffic goes: **block** (never reaches the network at all) and **direct** (bypasses the tunnel). Both accept the engine's full grammar — `example.com`, `full:`, `keyword:`, `regexp:`, `10.0.0.0/8`, `port:25`, `port:3000-3010` and `private`. Block is evaluated first, then direct, otherwise the tunnel is used.
- **Custom DNS inside the tunnel.** The resolvers used *inside* the tunnel are now configurable; leaving the field empty keeps the engine default (1.1.1.1, 1.0.0.1).
- **Internal improvements inherited for free.** `sysprofile.rs` tunes scan concurrency and socket buffers to the device's CPU/RAM tier, and `apifront.rs` presents legacy TLS fingerprints to blend into censored networks. Neither needs a setting, and neither weakens the tunnel's own cryptography.

### ℹ️ The engine version is now visible in About (parity with the Windows edition)

- The Windows edition's About page shows the **app version and the core version** side by side, so a user can verify the bundled engine is current. The Android About panel now does exactly the same: a new **"Engine (core) version"** row sits under the app version.
- The value comes from `BuildConfig.CORE_VERSION`, which is stamped at build time from `native/aether/CORE_VERSION` — i.e. from whatever the core-sync step actually vendored for *that* build. It can never drift from the engine that is really shipping.

### 🔐 Security

- **A new 0-to-100 security audit was performed for this release and scored 88/100.** The full report is at [`docs/SECURITY_AUDIT_1.2.3.md`](docs/SECURITY_AUDIT_1.2.3.md).
- **Organization secrets never touch the command line.** On Android any app can read `/proc/<pid>/cmdline` of a process it can see, so a service-token secret in argv would be far too widely readable. Only the non-secret team name and the `--gateway` flag travel as arguments; the client id, client secret, enrolment token and e-mail are handed to the engine through its **environment**, which is exactly where the core reads them.
- **Secrets are sealed with a hardware-backed key.** The new `SecretStore` encrypts the service-token secret and the enrolment JWT with a non-exportable **AES-256-GCM** key generated inside the **Android Keystore**, instead of leaving them in the plain DataStore preferences file where a backup or a rooted-device dump would expose them. The credential fields are masked in the UI.
- **The new free-form options are validated before they become arguments.** DNS entries and routing rules are checked against a strict allow-list, de-duplicated and hard-capped (8 resolvers, 256 rules), so pasted text containing whitespace or shell metacharacters can never split into extra engine arguments.
- **The Gateway toggle is labelled honestly.** Its description states that enabling it adds a hop inside the tunnel and lets the organization log your browsing — it is off unless you need it.

### 📦 Version

- App version **1.2.3**, version code **7** (per-ABI codes in the 7000 range). Same signing certificate as 1.2.2, so **1.2.2 users can install this directly over their existing app and keep all their settings** — no uninstall required.

---

## What's new in v1.2.2

This release moves engine maintenance into the build pipeline, **removes the in-app updater**, removes the country picker (it could never really choose a country) and hands endpoint selection back to the engine, and lands a broad performance, compatibility and security pass over the 1.2.1 code.

### 🔄 The build now owns the engine version (CI/CD core auto-upgrade)

- **Automatic core synchronisation on every build.** A new `scripts/sync-core.sh` step runs in GitHub Actions before the natives are compiled. It queries the official [Aether Core repository](https://github.com/CluvexStudio/Aether), compares the latest upstream release against the version pinned in `native/aether/CORE_VERSION`, and upgrades the vendored engine automatically when a newer one exists. The engine is pinned at **v1.4** as the current stable reference.
- **App-specific engine patches are rebased, not copied.** The custom range-scanning code this app relies on (`prober.rs`, `wg_prober.rs`) is carried across an upgrade with a real **three-way merge** against the pristine upstream baseline cached in `native/aether/.upstream-baseline/`. Upstream API changes and the app's own additions therefore combine correctly, exactly like a rebase. If a file cannot be merged, the build keeps the **pure upstream** version (which is guaranteed to compile) and flags the run for manual review — it never forces a stale copy that would break compilation.
- **A core upgrade can never fail a release.** The previous engine is snapshotted before every upgrade. If the newly synced core does not compile for any reason at all, CI automatically rolls back to the engine the repository already shipped, rebuilds and continues, annotating the run. The new `CORE_VERSION` and the changelog are committed **only after the engine has actually built**, so the repository can never record an upgrade that does not work.
- **Fail-safe by design.** If GitHub is unreachable, or the upstream layout is unexpected, the build keeps the vendored core and continues — a network hiccup can never break a release. The sync only ever moves **forward**; it never downgrades.
- **Regression-tested offline.** `scripts/test-core-sync.sh` replays the whole upgrade path against a local repository (upstream adding struct fields and function arguments, an unmergeable rewrite, and a downgrade attempt) so the sync logic is verified without touching the network.
- **The build toolchain is pinned.** Gradle is fixed at **8.9** to match AGP 8.7.2 instead of following whatever version the CI runner image happens to ship, and the release build gets an explicit 4 GB heap — the toolchain can no longer drift underneath the project.
- **New engine capabilities are surfaced, not hidden.** After an upgrade the script scans the new core for command-line capabilities that the UI does not expose yet and reports them in the build log and in this changelog, so no engine feature can ship without a matching UI decision.
- **The build documents itself.** After a successful upgrade, CI edits this README (and the Persian one), records the new core version in this section, and commits the change back to the branch. The shipped engine version and the documentation can no longer drift apart.
- **The engine version is visible in the app.** A `CORE_VERSION` build field replaces the old repository constant and is shown in the About panel.

### 🗑️ In-app update system removed

> **For the security of our users, for complete transparency, and to guarantee the authenticity of the code they receive, the direct in-app update capability has been removed. From now on, all updates will be available exclusively from the project's official GitHub Release page, officially signed — preventing any unwanted download from unknown sources.**

Concretely, this release deletes:

- `core/UpdateChecker.kt` — the module that queried GitHub and downloaded APKs in the background.
- `ui/components/UpdatePrompt.kt` — the in-app “update available / install now” card.
- The `REQUEST_INSTALL_PACKAGES` permission — **the app can no longer install anything**. This was the single highest-risk permission in 1.2.1.
- The `FileProvider` component and `res/xml/file_paths.xml` — they existed only to hand a downloaded APK to the system installer.
- The six `update_*` UI strings in both locales.

The app now carries a read-only `RELEASES_URL` pointer to the official releases page. There is **no code path left in Aether that can fetch or execute a new binary.**

### 🌐 No server/location list — the engine picks the endpoint itself

Aether has no country list and no server list, and it never had a real one. It connects you to **Cloudflare's WARP network**, whose addresses are **anycast**: the very same IP is announced from every Cloudflare datacenter at once, so the datacenter that answers is decided by your operator's routing — not by the app. A country menu in the app could only ever be a label; it could not move you to that country. That is why the picker (and its whole catalogue) was removed in this release.

- **The engine chooses the endpoint.** It scans its built-in WARP ranges, measures them and connects to whichever edge answers best on your network at that moment.
- **Smart Auto still does its real job:** fingerprinting the network's DPI and choosing the protocol / obfuscation ladder.
- **Your own settings still win.** A hand-typed peer (`ip:port`) or a custom scan range in Settings pins the scan exactly as before, and quick reconnect works normally.
- **No forced IP filtering.** Blocking exits by country is not reliable either — an experimental "never Iran" gate rejected almost every edge on real Iranian networks and left the app retrying until it gave up, so it was removed. Connection reliability comes first.

### ⚡ Stability, resource usage and compatibility

- **Memory leak fixed in the diagnostics log.** The log used to grow an immutable list without limit for the whole session — on a long connection it consumed steadily more RAM and made every new line more expensive. It is now a bounded 800-line ring buffer, UI updates are throttled to ~5 per second instead of one recomposition per line, and disk writes are batched onto a background thread. The log file itself is capped at 512 KiB with rotation.
- **Idle CPU overhead cut sharply.** The VPN supervisor used to wake up every two seconds for the entire session just to ask whether the engine was alive. It now **blocks** on the engine process itself and wakes only when the process actually exits — so crash detection is *faster* than before while the CPU can stay in deep idle.
- **No more busy-waiting after connect.** The 100-second, 250-millisecond polling loop that waited for the IP lookup was replaced with a proper suspending wait.
- **Fewer threads during connection.** Geolocation probes now share a single low-priority thread pool instead of spawning a fresh thread per provider on every attempt, and port probing backs off adaptively instead of polling at a fixed rate.
- **v2rayNG conflict resolved.** Aether's LAN-sharing bridge used ports **10808/10809** — exactly v2rayNG's default SOCKS and HTTP ports. With both apps installed, whichever started second failed to bind, or traffic was silently handed to the other tool's core. Aether now uses **10810/10811**, and additionally detects known neighbours (v2rayNG, Clash, Psiphon, Privoxy) before binding, naming the responsible app in the error message instead of showing a generic “port busy” failure.
- **Signature / over-install issue fixed at the root.** Android refuses an update when the new APK is signed with a different certificate, which is what forced 1.2.1 users to uninstall and lose their settings. CI now extracts the signer's SHA-256 fingerprint from every release APK and compares it against a pinned value in `.github/expected-signer.txt`; if the certificate ever changes, **the build fails instead of publishing an APK users cannot install**. Combined with the persisted stable keystore, **1.2.1 users can install 1.2.2 directly over their existing app and keep all their settings** — no uninstall required. Version code moved from 5 to 6 (monotonic per-ABI codes in the 6000 range).

- **Switching protocol no longer stalls the app.** Disconnecting and then connecting on a different protocol felt like it "took forever to start", on every protocol except Smart. Two real bugs caused it. First, stopping the engine was fire-and-forget: the app sent the process a polite terminate signal and immediately moved on, so the old engine was often still alive and still holding the local SOCKS5 port `127.0.0.1:1819` while the next one was already starting — the new attempt either could not bind or verified itself against the dying socket and had to time out and retry. The engine is now really waited for (and force-killed if it does not exit), and a new attempt waits for the local port to be released before it starts. Second, a connect tapped while the previous session was still winding down was **silently dropped** ("a run is already active → return"), which is exactly the "I pressed connect and nothing happened for a while" symptom; the new session now takes ownership, joins the old one, tears it down and starts immediately.
- **MASQUE (and any hand-picked protocol) gets a real second chance.** A protocol chosen by hand used to get exactly **one** attempt with the full scan budget of the selected scan mode — up to 150 s on Balanced, 300 s on Thorough — and no fallback, while Smart mode walks a ladder of short, hardened attempts. On a network where UDP/QUIC is throttled that meant staring at "Connecting" for minutes and then failing. Now the chosen protocol runs a capped first pass exactly as configured, and if that fails the **same** protocol is retried with anti-DPI hardening (obfuscation on, plus HTTP/2, TLS fragmentation and ECH for MASQUE) on the full budget. The protocol you picked is never swapped for another one.
- **Disconnect is instant again (30–50 s freeze fixed at the root).** Tapping disconnect could leave the button on "Disconnecting…" for up to a minute on every protocol. Root cause: the service supervisor parks on the engine process with `Process.waitFor`, which is a **blocking** Java call — and coroutine cancellation cannot interrupt a blocking call. The teardown asked the session to stop and then *waited for it to finish before killing anything*, so it sat inside that wait until the whole 60-second window expired (the log shows the engine being stopped exactly 60.0 s after connect, not when the button was tapped). Two changes fix it for good: the engine wait is now interruptible, so cancellation aborts it in milliseconds, and the teardown order was inverted — cancel, kill the natives immediately, flip the UI to idle, and only *then* reap the finished coroutine off the critical path. Reconnecting on another protocol follows the same order, so it no longer inherits the old session’s wait either. Stopping the engine also escalates to a hard kill after 250 ms instead of waiting seconds.
- **The release pipeline can no longer be broken by files left over from 1.2.1.** Copying 1.2.2 over an existing 1.2.1 repository overwrites changed files but cannot delete the files 1.2.2 *removed* (the in-app updater, the location picker, the forced-exit policy). Those orphans still referenced symbols and string resources that no longer exist, so `compileReleaseKotlin` failed with `Unresolved reference 'GITHUB_REPO'`, `'update_available'` and friends — which is exactly why the same sources built fine in a fresh repository and failed in the real one. The build now purges them itself: `scripts/purge-stale-sources.sh` deletes every path listed in `.github/removed-sources.txt` before compiling, commits the cleanup back to the branch, and additionally hard-fails in one second with a precise file+line message if *any* Kotlin source still references a string resource that does not exist.
- **The signing certificate of 1.2.1 is now protected by the build itself.** Android only installs an update when the old and the new APK carry the same signature, so the key must never change. Two guards were added: the build refuses to mint a new CI keystore in a repository that has already published with one (it stops with an explicit “restore `.github/ci-keystore.jks.b64`” error instead of silently producing an uninstallable APK), and the signer fingerprint is now pinned and enforced in CI-key mode too, per repository (`.github/expected-signer-ci.txt`). If the certificate ever differs from the one the previous release shipped with, the build fails instead of publishing. Nothing about this leaks between repositories: a scratch repository pins its own value.
- **The aurora animation is gone; the background is now a flat colour.** Three large radial gradients were being composited full-screen behind every screen for as long as the app was open. Even after the redraw rate was capped it still cost real GPU and CPU work on every frame budget the UI needed. The backdrop is now a single static fill that never invalidates — no animation runs behind the UI any more, so menus, sheets and the connect screen get the whole frame budget.
- **The main menu no longer runs while it is closed.** Android's navigation drawer composes its contents even when the drawer is shut, so the diagnostics, sharing, advanced and about cards were live at all times, recomposing on every settings change and on every engine log line behind a panel nobody was looking at. They are now built only when the drawer is actually open.
- **The diagnostics log only subscribes while it is open.** During a scan the engine emits hundreds of log lines; each one used to recompose the whole diagnostics card — and the drawer around it — even with the log console collapsed. Only the open console listens now.
- **The Advanced sheet opens instantly.** Its ~40 controls used to be laid out in the same frame the sheet starts its slide-in animation, which visibly stuttered the opening. The sheet now animates in first and fills itself immediately afterwards.

### 🔒 Full security audit

**This project has undergone a 100% line-by-line security audit for v1.2.2, and the critical security vulnerabilities identified have been remediated according to mobile-application audit standards.** The complete report is published at [`docs/SECURITY_AUDIT_1.2.2.md`](docs/SECURITY_AUDIT_1.2.2.md) and covers every mandated area:

| Area | Result |
|---|---|
| Hardcoded secrets & keys | **Clean.** No API keys, tokens, passwords or private keys in the app; all tunnel key material is generated at runtime inside the engine. |
| Cryptography & protocols | **Clean.** No weak or deprecated primitives, no custom `TrustManager`/`HostnameVerifier`, no downgrade path; tunnel security rests on Noise/QUIC + TLS 1.3 key authentication. |
| MitM exposure | **Not exploitable.** The only plaintext request is an anonymous IP-echo probe carrying no user data; tampering with it can at most show the wrong flag. |
| DNS / IPv6 / real-IP leaks | **None.** Both IPv4 and IPv6 default routes are captured by the tunnel (the classic IPv6 leak is closed), and DNS-through-tunnel is actively verified before the UI reports “Connected”. |
| Traffic bypass | **User-controlled only.** Split tunnelling is off by default; nothing leaves the tunnel unless you configure it. |
| Local storage | **App-private.** Only preferences are stored — no credentials, no keys — and the log file is now bounded and rotated. |
| Permissions & manifest | **Minimal.** `REQUEST_INSTALL_PACKAGES` removed; no location/contacts/storage/`QUERY_ALL_PACKAGES`; `android:debuggable` absent in release; `allowBackup` constrained by backup rules that exclude settings and logs; only the launcher activity is exported. |
| Logging | **Safe.** Logcat output is compiled out of release builds, and no keys, DNS queries, hostnames or packet payloads are ever written. |
| Dependencies & network config | **Hardened.** No ad, analytics or crash SDKs; native cores are built from source in CI; cleartext traffic is denied by default and user-installed CAs are not trusted. |

One risk is accepted and disclosed openly rather than hidden: the fallback CI keystore in the repository is public by design — it guarantees update continuity, not authenticity. Always download from the official Releases page and verify the signer fingerprint.


<details>
<summary>🇮🇷 همین بخش به فارسی (ممیزی کامل امنیتی)</summary>

<div dir="rtl">

#### 🔒 ممیزی کامل امنیتی

**این پروژه برای نسخهٔ ۱.۲.۲ تحت ممیزی امنیتی ۱۰۰ درصدی و خط‌به‌خط قرار گرفته و آسیب‌پذیری‌های امنیتی بحرانی شناسایی‌شده بر اساس استانداردهای ممیزی اپلیکیشن‌های موبایل رفع شده‌اند.** گزارش کامل در فایل [`docs/SECURITY_AUDIT_1.2.2.md`](docs/SECURITY_AUDIT_1.2.2.md) منتشر شده و تمام سرفصل‌های الزامی را پوشش می‌دهد:

| حوزهٔ بررسی | نتیجه |
|---|---|
| کلیدها و اطلاعات حساس هاردکدشده | **پاک.** هیچ API Key، توکن، رمز عبور یا کلید خصوصی در برنامه وجود ندارد؛ تمام کلیدهای تونل در زمان اجرا داخل خود هسته تولید می‌شوند. |
| رمزنگاری و پروتکل‌ها | **پاک.** هیچ الگوریتم ضعیف یا منسوخی استفاده نشده، هیچ <span dir="ltr">`TrustManager`</span>/<span dir="ltr">`HostnameVerifier`</span> سفارشی وجود ندارد و مسیر تنزل نسخه (Downgrade) باز نیست؛ امنیت تونل بر پایهٔ احراز کلید Noise/QUIC و TLS 1.3 است. |
| امکان حملهٔ MitM | **قابل بهره‌برداری نیست.** تنها درخواست بدون رمزنگاری، یک پراب ناشناس تشخیص آی‌پی است که هیچ دادهٔ کاربری حمل نمی‌کند؛ دستکاری آن حداکثر باعث نمایش پرچم اشتباه می‌شود. |
| نشت DNS / IPv6 / آی‌پی اصلی | **وجود ندارد.** هر دو مسیر پیش‌فرض IPv4 و IPv6 توسط تونل گرفته می‌شوند (نشت کلاسیک IPv6 بسته شده) و عبور DNS از داخل تونل پیش از اعلام «متصل» به‌صورت فعال راستی‌آزمایی می‌شود. |
| عبور ترافیک از خارج تونل | **فقط با اجازهٔ کاربر.** تانل‌کردن تفکیکی به‌صورت پیش‌فرض خاموش است و تا زمانی که خودتان تنظیم نکنید هیچ ترافیکی بیرون از تونل نمی‌رود. |
| ذخیره‌سازی محلی | **خصوصی برنامه.** فقط تنظیمات ذخیره می‌شود — نه اعتبارنامه‌ای و نه کلیدی — و فایل لاگ اکنون محدود و چرخشی است. |
| مجوزها و مانیفست | **حداقلی.** مجوز <span dir="ltr">`REQUEST_INSTALL_PACKAGES`</span> حذف شد؛ هیچ دسترسی موقعیت مکانی/مخاطبین/حافظه/<span dir="ltr">`QUERY_ALL_PACKAGES`</span> درخواست نمی‌شود؛ <span dir="ltr">`android:debuggable`</span> در نسخهٔ انتشار وجود ندارد؛ <span dir="ltr">`allowBackup`</span> با قواعد پشتیبان‌گیری محدود شده که تنظیمات و لاگ‌ها را مستثنا می‌کند؛ تنها اکتیویتی اجراکننده export شده است. |
| مدیریت لاگ | **ایمن.** خروجی Logcat در نسخهٔ انتشار اساساً کامپایل نمی‌شود و هیچ کلید، پرس‌وجوی DNS، نام دامنه یا محتوای بسته‌ای ثبت نمی‌گردد. |
| کتابخانه‌ها و پیکربندی شبکه | **مقاوم‌سازی‌شده.** هیچ SDK تبلیغاتی، آنالیتیکس یا گزارش کرش وجود ندارد؛ هسته‌های نیتیو در CI از روی سورس بیلد می‌شوند؛ ترافیک بدون رمزنگاری به‌صورت پیش‌فرض مسدود است و گواهی‌های نصب‌شده توسط کاربر مورد اعتماد برنامه نیستند. |

یک ریسک نیز به‌جای پنهان‌کاری، شفاف اعلام شده است: کی‌استور پشتیبانِ CI که در مخزن قرار دارد عمداً عمومی است — این کی‌استور تداوم به‌روزرسانی را تضمین می‌کند، نه اصالت را. همیشه از صفحهٔ رسمی انتشار دانلود کنید و اثر انگشت امضاکننده را بررسی نمایید.

</div>

</details>

---

## What's new in v1.2.1

This release makes connecting **faster and more honest**, fixes Persian-locale text bugs, and hardens security.

- **"Connected" now means connected** — the app stays in a new **"Verifying connection…"** state until all four health checks (port, handshake, internet, IP) pass. No more being told you're connected while nothing actually loads yet.
- **Fix for Auto mode & introduction of a truly intelligent engine (Smart Auto)** — Previously, Auto mode sent no protocol flags to the engine, relying blindly on engine defaults and failing on restricted networks. The new **Smart Auto** module analyzes the network like an engineer before initiating:
  - **Network DPI Discovery (prior to running the engine):** Sends parallel, multi-second probes over the actual operator path to assess UDP health (live DNS queries to `1.1.1.1` and `8.8.8.8`), test SNI-DPI (full TLS handshake with SNI to Cloudflare), measure TCP latency for individual WARP IP ranges, and detect carrier details (name, country code, and network type) without requiring any extra permissions.
  - **Network Classification:** Classifies the network into one of four classes: Open, SNI Filtering, UDP Throttling, or Hostile.
  - **Strategy Ladder:** Generates an prioritized list of "Protocol + Obfuscation (Noize) + Fragment/ECH + Successful IP Ranges" for each network class, from most to least likely to succeed, plus a final fallback attempt.
  - **Step-by-Step Connection:** Attempts each strategy sequentially, verifying it with the 4-step health check. The first strategy to pass is chosen and locked for automatic reconnects.
  - **Key Notes:** Active-range selection ensures quick attempts; lightweight obfuscation is auto-applied on Iranian mobile networks; user-defined custom endpoints/ranges are never overwritten; and all probe decisions are logged transparently. A new UI status **"Analyzing network (Smart connect)…"** has also been added.
- **IP & flag appear much faster** — the three IP-lookup services are now queried **in parallel** (the fastest one on *your* network wins) instead of one-by-one, the self-test checks run concurrently, and retry delays are shorter. This especially helps networks where DPI slows some providers down.
- **Fixed digits getting scrambled** while typing in the custom IP-range and manual endpoint fields (and everywhere `ip:port` is displayed) when the phone language is Persian — a right-to-left (BiDi) text issue, fixed at the root.
- **New Reset button** at the bottom of advanced settings — one tap restores every setting to its defaults.
- **Security hardening** (full audit report in `docs/SECURITY_AUDIT.md`): TLS hostname verification on the built-in probes (blocks man-in-the-middle), engine output no longer mirrored to Logcat in release builds, cleartext HTTP denied app-wide, stricter backup rules.
- **In-app updates (Telegram-style) — beta** — when a new version is published on GitHub Releases, the app itself shows an "Update" banner on the home screen; one tap downloads the right APK for your device and opens the installer. No need to visit GitHub. **This feature is currently in beta (experimental)** and is still being stabilized.
- **Proper release signing — beta** — every build is signed with one stable key (the repo's CI keystore, or your own via `keystore.properties` / repo Secrets; see `docs/SIGNING.md`), so updates keep installing right on top with no uninstall. **The signing mechanism is likewise in beta (experimental)** while it is validated across devices.

## What's new in v1.2.0

This release ships the full advanced feature set and makes in-place updates reliable.

- **Amnezia-style anti-DPI obfuscation (Noize)** — Off / Light / Firewall / Balanced / GFW / Aggressive, to defeat protocol fingerprinting on heavily filtered networks.
- **New "Ironclad" scan mode** — the most persistent endpoint search, for the hardest networks (adds to Turbo / Balanced / Thorough / Stealth).
- **Endpoint selection** — Auto (scan the built-in ranges), Manual peer (pin one `ip:port`), or **Custom range** (type your own IP range(s) and the engine scans *exactly* those, e.g. `8.6.112.x`, `188.114.96.0/24`).
- **WireGuard keepalive**, **adjustable MTU** (default 1280, best for Iranian mobile / aggressive DPI), **TLS ClientHello fragmentation**, and **Encrypted Client Hello (ECH)**.
- **Proxy mode** — run the engine + a local SOCKS5/HTTP proxy *without* capturing the whole device through the system VPN.
- **Per-app split tunneling** — pick exactly which apps use (or skip) the tunnel, with a built-in app picker.
- **Fixes:** correct "your IP" readout (no more cellular IPv6) and correct upload/download traffic figures.
- **Reliable in-place updates** — the signing key is persisted inside your repo, so as long as you keep building in the **same** repository, new versions install right on top of the old one with no uninstall. See [Updates & app signing](#updates--app-signing).
- **Fixed the advanced-settings sheet** — it now scrolls cleanly to the last control without clipping behind the navigation bar.
- **Fixed full-device VPN mode** — CI now packages and verifies the embedded TUN-to-SOCKS core and all of its runtime dependencies in every APK.

## What's new in v1.1.0

- **Quick Settings tile** — connect/disconnect right from the notification shade without opening the app. Add it once: swipe down → tap the pencil/edit button in Quick Settings → drag the **Aether** tile into your tiles.
- **Share the VPN with your laptop or another phone** — side menu → **Share VPN**. See [Share the VPN](#share-the-vpn-with-your-laptop-or-another-phone) below.
- **Advanced settings on the home screen** — the tune button (top-right) opens all advanced options in a bottom sheet.
- **In-place updates** — builds are now signed with one stable key so future updates install right on top. See [Updates & app signing](#updates--app-signing).

## What this is

The Windows version of Aether works like this: the app runs the **Aether engine**, which opens a local **SOCKS5 proxy at `127.0.0.1:1819`**. You then paste that address into **v2rayNG** and let it tunnel your traffic.

**Aether Mobile removes the second step entirely.** The app:

1. runs the same Aether engine internally (opens SOCKS5 on `127.0.0.1:1819`),
2. brings up an Android **VpnService** (a system TUN interface),
3. uses a **built-in tunnel core** (`hev-socks5-tunnel`) to forward every packet from the TUN device into that local SOCKS5 proxy.

So the whole “engine + v2rayNG” chain now lives in one app. One tap connects everything.

```
  Your apps  ─►  Android VPN (TUN)  ─►  hev-socks5-tunnel  ─►  127.0.0.1:1819  ─►  Aether engine  ─►  Internet
                     (built in)          (built in, replaces v2rayNG)      (SOCKS5)          (built in)
```

## How this VPN actually works — and where your data goes (plain English)

A lot of users ask: *“It's a free VPN with no server list… whose server am I connecting to? Who sees my data?”* Fair question. Here's the honest, simple answer:

- **There is no secret middleman server.** Aether does not route you through some stranger's VPS. The engine connects your device to **Cloudflare's WARP network** — the same worldwide infrastructure behind the famous **1.1.0.1 / WARP** app used by millions. That IS the “destination server”: Cloudflare's public edge, not something run by us.
- **Your traffic is encrypted on your phone** using the WireGuard or MASQUE protocol **before it leaves the device**, and is decrypted only inside Cloudflare's network on its way to the website you're visiting. Anyone in between (your ISP, the Wi‑Fi owner) sees only encrypted noise.
- **The app developers run no servers and receive none of your traffic.** There are no analytics, no accounts, no logging back to us — nothing to send, nowhere to send it. What the engine really adds is smart *endpoint scanning and obfuscation* so WARP keeps working on heavily filtered networks.
- **Trust, but verify:** the entire app and the engine are open source ([this repo](https://github.com/QW-AI-Code) + [CluvexStudio/Aether](https://github.com/CluvexStudio/Aether)). Anyone can read the code and confirm the above; the APKs are built publicly by GitHub Actions straight from this source.
- **The honest caveat:** like ANY VPN, the operator of the exit network — here, Cloudflare — can technically see the traffic that exits through it (Cloudflare publishes its [privacy policy](https://www.cloudflare.com/application/privacy/) for WARP). Websites you visit over HTTPS stay end-to-end encrypted regardless. If your threat model can't accept Cloudflare, no WARP-based tool is for you.

**TL;DR:** your data goes: *your phone → (encrypted) → Cloudflare WARP → the website*. The developers are not in that path at all.

## Highlights

- **Material You dark UI** built with Jetpack Compose. Uses the wallpaper-based **dynamic color** on Android 12+, and falls back to a beautiful deep-navy palette on older devices.
- **Two network backends:** **Aether**, and the chained **`Aether → Psiphon`** for a real foreign exit IP (with an exit-country picker) when an Iranian or Cloudflare-anycast address is what a site is refusing.
- Glowing connect button with a large tick when you are through, a travelling primary-colour light around the connection card, and a home screen that always fits on one screen with nothing to scroll.
- All the same options as the desktop app **and more**: **protocol** (Smart / MASQUE / WireGuard / Gool), **scan mode** (Turbo / Balanced / Thorough / Stealth / **Ironclad**), **IP version** (v4 / v6 / both), **quick reconnect** and **MASQUE over HTTP/2** — plus **Amnezia-style obfuscation (Noize)**, **manual endpoint / custom scan range**, **keepalive**, **MTU**, **TLS fragmentation**, **ECH**, **proxy mode** and **per-app split tunneling**. All reachable both from the side menu and straight from the **home screen** (tune button, top-right).
- **Quick Settings tile** for one-swipe connect/disconnect.
- **VPN sharing over Wi‑Fi/hotspot** — built-in HTTP + SOCKS5 proxy for your other devices.
- Auto-reconnect with backoff if the engine drops.
- Bilingual (English + Persian) with automatic RTL.

## Quick Settings tile (one-swipe on/off)

1. Swipe down from the top of the screen to open Quick Settings.
2. Tap the **pencil / edit** button and drag the **Aether** tile into your active tiles (needed once).
3. From then on: **tap the tile to connect, tap again to disconnect** — no need to open the app. The very first connection must still be started from the app once, so Android can show its standard VPN permission dialog.

## Share the VPN with your laptop or another phone

Your phone can act as a **gateway** for other devices on the same Wi‑Fi network or on your phone's hotspot:

1. Connect the VPN in Aether.
2. Open the side menu → **Share VPN** → turn on **Share on this network**.
3. The panel shows two addresses (tap the copy icon next to either):
   - **HTTP proxy — `<your-phone-ip>:10809`** → enter this in the other device's **system proxy** settings (Windows: Settings → Network → Proxy → Manual; macOS: Wi‑Fi → Details → Proxies → Web/Secure Web Proxy; Android/iOS: Wi‑Fi → Modify network → Proxy → Manual).
   - **SOCKS5 proxy — `<your-phone-ip>:10808`** → for apps/browsers that support SOCKS (e.g. Firefox, Telegram).
4. Done — the other device's traffic now goes through your phone's tunnel.

> ⚠️ While sharing is on, **anyone on that network** can use the proxy. Only enable it on networks you trust (your own hotspot is safest). Sharing stops automatically when the VPN disconnects.

## Updates & app signing (do I have to uninstall old versions?)

Android installs an update **on top of** the old app only if both are signed with the **same key**. Older builds of this project fell back to a **temporary debug key that changed between builds**, which is why updating used to demand a full uninstall.

**Fixed in v1.1.0.** The CI now always signs with one **stable key**:

- If you set the keystore Secrets (table below), your own key is used — recommended.
- Otherwise, the very first build generates a CI keystore and **commits it to the repo** (`.github/ci-keystore.jks.b64`); every later build reuses that exact key.

What that means for users:

- **From v1.1.0 onward:** just download the new APK and install — it updates in place, data intact. No uninstalling, ever.
- **Upgrading from v1.0.0 (or older):** one final uninstall is required, because those builds were signed with the old throwaway key. After that, never again.

> ♻️ **Beginner tip — keep the same repo & keep the key.** The stable key lives in `.github/ci-keystore.jks.b64` inside *your* repository. When you upload a newer source drop, add/replace the changed files in the **same** repo and **do not delete** that keystore file — that is exactly what lets a new version install on top of the one already on your phone. If you ever start a brand-new repo (or the file gets removed), the next build makes a fresh key, so that one time you'll need a single uninstall; after that it's permanent again.

> 🔒 **Security note:** a keystore committed to a public repo is public — it guarantees *updatability*, not *authenticity* (anyone could sign an APK with it). If you distribute this app seriously, set the Secrets below; they always take priority over the repo keystore.

| Secret | Meaning |
|--------|---------|
| `KEYSTORE_BASE64` | Your `.jks` keystore, base64-encoded |
| `KEYSTORE_PASSWORD` | Keystore password |
| `KEY_ALIAS` | Key alias |
| `KEY_PASSWORD` | Key password |

Create the base64 with: `base64 -w0 my-release-key.jks > keystore.txt`, then add the Secrets under Settings → Secrets and variables → Actions.

## Build it on GitHub (no computer setup needed)

You do **not** need Android Studio. GitHub Actions builds everything for you.

1. Create a new GitHub repository and upload the contents of this folder (or push it with git).
2. Go to the repo's **Actions** tab and enable workflows if prompted.
3. Every push to `main` builds the app. To get installable release files, create a version tag:
   ```bash
   git tag v1.2.0
   git push origin v1.2.0
   ```
4. When the build finishes, the **Releases** page will contain three APKs (see below). Release titles are clean (`Aether v1.2.0`) and the “What's new” text comes from `.github/release-notes.md` — update that file together with the version.

### The three APKs

| File | For which phone |
|------|-----------------|
| `Aether-1.2.0-arm64-v8a.apk` | Almost all modern phones (64-bit ARM) |
| `Aether-1.2.0-armeabi-v7a.apk` | Older / low-end 32-bit phones |
| `Aether-1.2.0-universal.apk` | **If you're not sure, download this one.** Works on any ARM phone |

## How the native parts are built

Upstream Aether does **not** publish Android binaries, so CI builds them from source:

- `scripts/fetch-natives.sh` clones `hev-socks5-tunnel` (the in-app tunnel) and the Aether engine source.
- `scripts/build-natives.sh` builds hev with `ndk-build` into `libhev-socks5-tunnel.so` and cross-compiles the Aether engine with `cargo-ndk` into `libaether.so`, for both `arm64-v8a` and `armeabi-v7a`.
- Before publishing, CI checks every APK and refuses the release unless both native cores are actually present for every included ABI.

You can pin versions via env vars: `HEV_REF`, `AETHER_REPO`, `AETHER_REF`.

## Requirements

- Android 8.0 (API 26) or newer.
- The app asks for VPN permission (standard Android consent) and notification permission (to show the ongoing status).

## Credits & license

- [CluvexStudio/Aether](https://github.com/CluvexStudio/Aether) — the engine.
- [heiher/hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) — the tunnel core.

Released under **AGPL-3.0**. See [LICENSE](LICENSE).


## Gemini AI (1.2.9)

Bring your own free Gemini key and the app gains four things. All of them are
optional, all of them are off until you enter a key, and none of them changes how
the tunnel works.

**1. Your own API key, your own models.** Paste a free key from
[Google AI Studio](https://aistudio.google.com/apikey) into *Settings -> Assistant*
and press **Test the API connection**. The app asks your key which models it may
use and shows exactly that list - a free key, a billing-enabled key and a key from
a region where a model has not launched all see different models, so nothing is
hard-coded. A model is picked for you; you can change it.

The key is sealed with a hardware-backed AES-GCM key from the Android Keystore
(`SecretStore`), never written to the plain settings file, never written to the
diagnostics log, and sent to Google in a request **header** rather than a query
string. Resetting the app settings does not delete it.

**2. Settings advice from your own log.** After each connect - or on demand from
*Assistant -> Settings advisor* - Gemini reads a redacted excerpt of this session's
log, reports what your operator's inspection appears to be doing to the
connection, and proposes the settings that fit it. This **optimises the app's own
options**; it is not advice about anyone else's infrastructure.

What is redacted before anything leaves the device: credentials of any shape
(including your own Gemini key), and every public IPv4 masked to its /16. Private
and loopback addresses are kept, because `127.0.0.1:1819` is the most diagnostic
string in the whole log. Nothing is applied until you press **Apply**, unless you
switch automatic apply on yourself.

**3. An AI icon next to every option.** Tap the small mark beside any setting and
you get: what it is, what it is for, and how to use it - in your language. Each
answer is grounded in a factual description of that setting written from the
engine's real behaviour, so the model explains something true instead of guessing
what "Noize" or "Scan mode" might mean in a VPN app. The factual description shows
instantly, with or without a key; Gemini's answer arrives underneath it. Answers
are cached, so reopening a sheet costs no quota. Switch the icons off in
*Assistant -> Behaviour*.

**4. A chat inside the app.** *Assistant -> Chat with Gemini*, or the AI button in
the top corner of the home screen. Ask anything. Ask it to change something and it
proposes a patch you approve with one tap.

### What the AI may and may not change

Tuning only: protocol, scan mode, IP family, obfuscation, MTU, keepalive, TLS
fragmentation and its ranges, ECH, MASQUE-over-HTTP/2, quick reconnect, in-tunnel
DNS, domain sniffing, TLS groups, validate/reconnect timings, reconnect limits,
kill switch, IPv6 leak protection, identity reprovisioning, exit country and the
core log level.

It can **never** change: the network backend, the upstream proxy, the block and
direct routing lists, a pinned manual endpoint or scan range, proxy mode, split
tunneling, blocked apps, LAN sharing, or any Zero Trust credential. Those decide
*which traffic is protected and where it goes*, and the allow-list that enforces
this is code (`ai/AiPatch.kt`), not prompt wording - a change the model cannot
express is a change it cannot make.

Applied changes are handed to the engine when it starts, so they take effect on
your **next connect**. The app says so every time.

### Why the AI only works while connected, in `Aether -> Psiphon`

Not a policy. Two facts:

- `AetherVpnService` calls `addDisallowedApplication` on this app's own package, so
  Aether's sockets deliberately bypass the tunnel it builds. A normal HTTP client
  would leave on the operator's network, in the clear, and fail - while announcing
  to that operator that this device just tried to reach a blocked AI endpoint. So
  every AI request is dialled through the tunnel's own local SOCKS5 proxy
  (`ai/GeminiHttp.kt`), with the destination sent as a **domain** so the exit
  resolves it, and TLS terminated on the device with hostname verification
  enforced. With the tunnel down there is no path at all.
- Google's AI endpoints refuse or challenge Cloudflare WARP exit addresses - the
  exact symptom 1.2.8 shipped release notes about. The chained mode exits through
  a Psiphon address, which they accept.

When either condition is missing, every AI surface says which one and offers the
button that fixes it.

### Dependencies added: none

JSON is `org.json` from the Android platform; HTTP, TLS and SOCKS5 are hand-rolled
over `java.net`, the same way `core/NetProbe.kt` already probes the tunnel. The
APK gains no third-party library.
