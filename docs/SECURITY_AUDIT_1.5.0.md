# Security audit — Aether 1.5.0

**Score: 92 / 100** (91.96, weighted over ten areas, same areas and weights as the 1.4.0 audit).
Release under review: **1.5.0 (version code 18), engine core 2.3.0, Xray-core v25.12.8**.
Signing is unchanged, so it installs over the current app without an uninstall.

**Target of review:** the 1.5.0 tree, with focus on what is new in this release:
custom configs (`custom/`: parser, NPVT decoder, `XrayConfigBuilder`, `XrayProcess`,
`ConfigPing`, `RealDelay`, `ConfigPingStore`, `ConfigImporter`), the chained session
paths in `vpn/AetherVpnService.kt`, the TUN builder (routes, DNS, IPv6, kill switch,
excluded apps), `AndroidManifest.xml`, `res/xml`, storage (`KeyVault`, `SecretStore`),
logging, the clipboard, and the release workflow.
**Method:** manual review of the security-relevant paths plus mechanical scans (custom
`TrustManager` / `HostnameVerifier`, cleartext policy, exported components,
`PendingIntent` mutability, `FLAG_SECURE`, sensitive clipboard, Logcat sinks, listeners
on loopback, sockets opened by the app process outside the VPN, CI download pinning).
The real-delay probe was additionally exercised against a local Xray server (REALITY and
WebSocket) and every generated probe config was validated with `xray run -test`.
**Scope of evidence:** static review plus unit/integration tests on a Linux host. There
was **no dynamic test on a phone** (no packet capture of a live session); the leak
statements below come from reading the TUN builder and routing code.

---

## 1. Scores

| # | Area | Weight | Score | Weighted |
| --- | --- | ---: | ---: | ---: |
| 1 | Secrets & key management | 14 | 95 | 13.30 |
| 2 | Cryptography, TLS & MitM resistance | 13 | 96 | 12.48 |
| 3 | Data-leak risk (DNS, IPv6, WebRTC, tunnel bypass) | 17 | 90 | 15.30 |
| 4 | LAN sharing surface (proxy, pages, WebRTC) | 10 | 86 | 8.60 |
| 5 | Local storage at rest | 12 | 93 | 11.16 |
| 6 | Permissions & OS configuration | 7 | 97 | 6.79 |
| 7 | Logging & diagnostics | 7 | 97 | 6.79 |
| 8 | Code quality & network configuration | 8 | 88 | 7.04 |
| 9 | On-device exposure (screen, clipboard) | 6 | 95 | 5.70 |
| 10 | Supply chain & build integrity | 6 | 80 | 4.80 |
| | **Total** | **100** | | **91.96 ≈ 92** |

Compared with 1.4.0 (also 92): areas 2, 7, 8 and 9 went up with the fixes below; area 3
went down because custom configs add a new local listener and a new bypass path that
had to be audited (SB-5, one of them fixed); area 5 went down slightly for the Xray
config file written during a session (SB-8); area 6 for the new, runtime-only `CAMERA`
permission.

## 2. Findings

| ID | Severity | Finding | Status |
| --- | --- | --- | --- |
| SB-1 | Low (functional: High) | The custom-config "real ping" opened its own TLS handshake with a trust-everything `TrustManager`, and reported working configs as `-1 · Filtered` | **Fixed in 1.5.0** |
| SB-2 | Medium | Xray log lines could put the server address and UUID of a **locked** NPVT config into the diagnostics log, defeating the author's lock | **Fixed in 1.5.0** |
| SB-3 | Medium | While connected, subscription downloads left on the bare network (the app is excluded from its own VPN), exposing the real IP and the subscription host to the ISP | **Fixed in 1.5.0** |
| SB-4 | Low | *Copy link* of a config was not flagged sensitive on the clipboard | **Fixed in 1.5.0** |
| SB-5 | Medium | The session's local SOCKS listeners on `127.0.0.1` (engine, Xray, Tor/Psiphon fronts) have no authentication; any app on the phone can use them, including apps excluded by split tunnelling | Open (documented) |
| SB-6 | Low | IPv6 leak protection can be switched off by the user in non-chained modes; with it off, IPv6 bypasses the tunnel on dual-stack networks | Accepted (on by default, forced on in chains) |
| SB-7 | Low | Proxy mode and split-tunnel-excluded apps bypass the tunnel by design, WebRTC included | Accepted (by design, documented) |
| SB-8 | Low | The generated Xray config (server, keys) is written in clear to app-private storage while a session runs | Accepted (app-private, deleted on stop) |
| SB-9 | Info | The code comment on `VpnService.Builder.setBlocking(true)` calls it a kill switch; it only sets blocking I/O. The real kill switch is the lockdown TUN | Info |
| SA-1 | Medium | LAN sharing has no password by default (carried over from 1.4.0) | Open by product decision |
| SA-3/4 | Low | Clear-text LAN proxy credentials; hotspot guest WebRTC (carried over) | Accepted / mitigated |
| SA-5 | Low | R8 / minification off for release builds | Open (deliberate) |
| SA-6 | Low | CI actions pinned by tag; `dtolnay/rust-toolchain@stable` follows a branch | Open |
| SA-7 | Info | No certificate pinning; system CAs only, user CAs refused | Accepted |
| SA-8 | Info | App signing out of scope | Not scored |

### SB-1 — Real-ping probe: trust-all TLS and wrong method (fixed)

`ConfigPing.measure` resolved the server with the network resolver, opened a TCP
socket and ran a TLS handshake with the platform TLS stack and a `TrustManager` that
accepted every certificate. No user data was sent over that handshake, so the security
impact was limited to a TLS client that could be impersonated, but the method was
wrong for the job: a **REALITY** server only completes a handshake for a client that
carries the config's public key and short ID, otherwise it relays to the camouflage
site or holds the connection; the ClientHello did not carry the config's uTLS
fingerprint; the name was resolved with a resolver that may be poisoned; and any
exception the code did not recognise was classified as a reset, i.e. **Filtered**. That
is why configs that connect in seconds showed `-1`.

Now the delay is measured **through the config** (`custom/RealDelay.kt`), as v2rayNG
does: one Xray-core process per batch with a loopback SOCKS inbound per config, routed
to the config's own outbound (same transport, TLS/REALITY, fingerprint, flow, DNS); an
HTTP `GET /generate_204` (gstatic, fallback Cloudflare) is timed through it. The hand-made
TLS handshake and the trust-all `TrustManager` were deleted; the only remaining
hand-rolled TLS probes in the app use the platform `HostnameVerifier`. After a failure,
`ConfigPing.diagnose` does a plain TCP check only to label the failure (*No response* /
*Server off* / *Filtered*). The probe's own lookups use the physical network's DNS,
which is the same information the real connection reveals when it starts.

### SB-2 — Locked NPVT server visible through Xray logs (fixed)

Xray prints the outbound target and, on some errors, the user ID. For a locked NPVT
config the app hides the server, keys and raw text in the UI, but those lines reached
the diagnostics log, which the user can export. `XrayProcess.redact` now masks UUIDs,
host names (except the probe hosts and file names), IPv6 literals and reduces IPv4 to
its /16 before anything is logged. Covered by `XrayLogRedactionTest`.

### SB-3 — Subscription fetch outside the tunnel (fixed)

The app's own package is excluded from the VPN so that Xray, the engine and Psiphon
never loop into the TUN. A side effect was that `ConfigImporter.download` (subscription
URL refresh) used the bare network even while connected. The service now publishes its
local forward port (`AetherVpnService.activeForwardPort`), and downloads made while a
session runs use `Proxy(SOCKS, 127.0.0.1:port)`, so they leave through the tunnel.

### SB-4 — Config link on the clipboard (fixed)

*Copy link* used the Compose clipboard, which cannot set `EXTRA_IS_SENSITIVE`, so the
link (server and key) appeared in the keyboard's clipboard preview and history. It now
goes through `copySensitive`, like every other secret.

### SB-5 — Unauthenticated loopback SOCKS (open)

Every chain mode needs local SOCKS hops on `127.0.0.1`. They are bound to loopback only
(never to the LAN; LAN sharing is a separate bridge with its own guard), but they are not
password-protected, so another app on the same phone can connect to them. Apps inside
the VPN gain nothing (their traffic is tunnelled anyway); the risk is an app **excluded**
by split tunnelling, or any app in proxy mode, using the port to reach the tunnel exit and
learning that a VPN is running and its exit IP. Adding RFC 1929 credentials to these hops
needs matching changes in the engine, the Tor/Psiphon fronts and tun2socks; the new
real-delay probe inbounds already use random per-run credentials. Recommended for the
next release.

### SB-6 .. SB-9

- **IPv6:** the TUN routes `::/0` whenever IPv6 leak protection is on (default) and always
  in chained / custom-config sessions, so IPv6 cannot bypass the tunnel there. A user who
  turns the option off in a single-engine mode accepts IPv6 going outside.
- **DNS:** the TUN advertises `1.1.1.1` / `8.8.8.8`, both routed into the TUN, so DNS goes
  through the tunnel; Tor modes carry DNS over TCP through the Tor SOCKS front and drop
  UDP. No path was found where a lookup of a tunnelled app reaches the ISP resolver.
- **WebRTC:** STUN is UDP inside the TUN, so it leaves through the tunnel exit (and is
  dropped in Tor modes); a WebRTC test shows the exit, not the real IP. Not true for
  excluded apps or proxy mode (SB-7).
- **Kill switch:** on an engine failure the service keeps a lockdown TUN that blackholes
  traffic; the TUN also stays up during engine restarts. Android's own *Always-on VPN* +
  *Block connections without VPN* remains the strongest guarantee and is recommended.
- **Xray config file (SB-8):** written to `filesDir` (mode private) for the Xray process
  and deleted on stop; the saved configs themselves are sealed with AES-256-GCM under an
  Android Keystore key.

## 3. Verified and holding

- `android:allowBackup="false"`, cleartext denied app-wide, system trust anchors only.
- Only `MainActivity` (launcher) and the Quick Settings tile are exported; the VPN service
  is `exported="false"` behind `BIND_VPN_SERVICE`; no content provider; every
  `PendingIntent` is `FLAG_IMMUTABLE`.
- Custom configs and secrets are sealed at rest (Keystore AES-256-GCM); NPVT locks
  (hidden server, password, expiry, mobile-data-only, device binding) are enforced in the
  app; `sanitizeOutbound` strips `dialerProxy`, `sockopt.mark` and `interface` from
  imported JSON so a config cannot redirect itself around the chain.
- Xray, the engine and Psiphon run as separate processes outside the VPN (loop
  prevention); their stdout reaches Logcat only in debug builds.
- `FLAG_SECURE` on the sensitive screens; copied secrets and config links are flagged
  sensitive.
- The Xray release archive fetched by CI is checked against a pinned SHA-256; CI refuses
  an APK that lacks `libxray.so` or the engine library for any ABI.

## 4. What still needs a device

A packet capture of a live session on a phone (DNS, IPv6, WebRTC with the TUN up, and the
kill switch during an engine restart) was not possible in this audit and is the
recommended next step. The real-delay test against the user's own REALITY sample
(`31.77.13.19:2063`) could not be confirmed from the audit host, whose outbound traffic
is filtered; it has to be checked on the phone.

---

<div dir="rtl" align="right">

<h2 dir="rtl" align="right">‏ممیزی امنیتی Aether نسخهٔ ۱.۵.۰ (فارسی)</h2>

<p dir="rtl" align="right">‏امتیاز این ممیزی <strong>۹۲ از ۱۰۰</strong> است (<span dir="ltr">91.96</span>، با همان ده حوزه و وزن‌های ممیزی <span dir="ltr">1.4.0</span>). نسخهٔ بررسی‌شده <span dir="ltr">1.5.0</span> با کد نسخهٔ <span dir="ltr">18</span>، هستهٔ موتور <span dir="ltr">2.3.0</span> و <span dir="ltr">Xray-core v25.12.8</span> است. امضا تغییر نکرده و برنامه بدون حذف روی نسخهٔ فعلی نصب می‌شود.</p>

<p dir="rtl" align="right">‏<strong>روش:</strong> بازبینی دستی مسیرهای حساس امنیتی (کانفیگ‌های سفارشی، زنجیره‌ها، ساخت <span dir="ltr">TUN</span>، مسیرها، <span dir="ltr">DNS</span>، <span dir="ltr">IPv6</span>، کیل‌سوییچ، ذخیره‌سازی، لاگ، کلیپ‌بورد و <span dir="ltr">CI</span>) به‌همراه جست‌وجوی خودکار الگوهای خطرناک. تست تأخیر واقعی جدید روی یک سرور محلی <span dir="ltr">Xray</span> (<span dir="ltr">REALITY</span> و <span dir="ltr">WebSocket</span>) اجرا شد و همهٔ کانفیگ‌های ساخته‌شده با <span dir="ltr">xray run -test</span> تأیید شدند. <strong>آزمون پویا روی گوشی (ضبط بسته‌ها) انجام نشده است</strong>؛ گزاره‌های مربوط به نشت از خواندن کد به دست آمده‌اند.</p>

<h3 dir="rtl" align="right">‏۱. امتیازها</h3>

<table dir="rtl" align="right">
<thead><tr><th align="right">#</th><th align="right">حوزه</th><th align="right">وزن</th><th align="right">امتیاز</th><th align="right">امتیاز وزنی</th></tr></thead>
<tbody>
<tr><td align="right">۱</td><td align="right">اسرار و مدیریت کلید</td><td align="right">۱۴</td><td align="right">۹۵</td><td align="right">۱۳٫۳۰</td></tr>
<tr><td align="right">۲</td><td align="right">رمزنگاری، <span dir="ltr">TLS</span> و مقاومت در برابر <span dir="ltr">MitM</span></td><td align="right">۱۳</td><td align="right">۹۶</td><td align="right">۱۲٫۴۸</td></tr>
<tr><td align="right">۳</td><td align="right">خطر نشت داده (<span dir="ltr">DNS</span>، <span dir="ltr">IPv6</span>، <span dir="ltr">WebRTC</span>، دور زدن تونل)</td><td align="right">۱۷</td><td align="right">۹۰</td><td align="right">۱۵٫۳۰</td></tr>
<tr><td align="right">۴</td><td align="right">سطح اشتراک روی شبکهٔ محلی</td><td align="right">۱۰</td><td align="right">۸۶</td><td align="right">۸٫۶۰</td></tr>
<tr><td align="right">۵</td><td align="right">ذخیره‌سازی محلی</td><td align="right">۱۲</td><td align="right">۹۳</td><td align="right">۱۱٫۱۶</td></tr>
<tr><td align="right">۶</td><td align="right">مجوزها و پیکربندی سیستم</td><td align="right">۷</td><td align="right">۹۷</td><td align="right">۶٫۷۹</td></tr>
<tr><td align="right">۷</td><td align="right">لاگ و عیب‌یابی</td><td align="right">۷</td><td align="right">۹۷</td><td align="right">۶٫۷۹</td></tr>
<tr><td align="right">۸</td><td align="right">کیفیت کد و پیکربندی شبکه</td><td align="right">۸</td><td align="right">۸۸</td><td align="right">۷٫۰۴</td></tr>
<tr><td align="right">۹</td><td align="right">افشا روی خود گوشی (صفحه، کلیپ‌بورد)</td><td align="right">۶</td><td align="right">۹۵</td><td align="right">۵٫۷۰</td></tr>
<tr><td align="right">۱۰</td><td align="right">زنجیرهٔ تأمین و سلامت بیلد</td><td align="right">۶</td><td align="right">۸۰</td><td align="right">۴٫۸۰</td></tr>
<tr><td align="right"></td><td align="right"><strong>جمع</strong></td><td align="right"><strong>۱۰۰</strong></td><td align="right"></td><td align="right"><strong><span dir="ltr">91.96</span> ≈ ۹۲</strong></td></tr>
</tbody>
</table>

<p dir="rtl" align="right">&nbsp;</p>

<h3 dir="rtl" align="right">‏۲. یافته‌ها</h3>

<table dir="rtl" align="right">
<thead><tr><th align="right">شناسه</th><th align="right">شدت</th><th align="right">یافته</th><th align="right">وضعیت</th></tr></thead>
<tbody>
<tr><td align="right"><span dir="ltr">SB-1</span></td><td align="right">کم (از نظر عملکرد: زیاد)</td><td align="right">«پینگ واقعی» کانفیگ‌ها یک دست‌دهی <span dir="ltr">TLS</span> جداگانه با <span dir="ltr">TrustManager</span>ِ «همه را بپذیر» انجام می‌داد و کانفیگ‌های سالم را <span dir="ltr">-1</span> و «فیلترشده» نشان می‌داد</td><td align="right"><strong>در <span dir="ltr">1.5.0</span> رفع شد</strong></td></tr>
<tr><td align="right"><span dir="ltr">SB-2</span></td><td align="right">متوسط</td><td align="right">خطوط لاگ <span dir="ltr">Xray</span> می‌توانست آدرس سرور و <span dir="ltr">UUID</span>ِ کانفیگ <strong>قفل‌شدهٔ</strong> <span dir="ltr">NPVT</span> را وارد گزارش عیب‌یابی کند و قفل سازنده را بی‌اثر کند</td><td align="right"><strong>رفع شد</strong></td></tr>
<tr><td align="right"><span dir="ltr">SB-3</span></td><td align="right">متوسط</td><td align="right">در حالت وصل، دریافت لینک اشتراک (<span dir="ltr">subscription</span>) از شبکهٔ بدون تونل انجام می‌شد و <span dir="ltr">IP</span> واقعی و آدرس اشتراک برای اپراتور دیده می‌شد</td><td align="right"><strong>رفع شد</strong></td></tr>
<tr><td align="right"><span dir="ltr">SB-4</span></td><td align="right">کم</td><td align="right">«کپی لینک» کانفیگ در کلیپ‌بورد حساس علامت نمی‌خورد</td><td align="right"><strong>رفع شد</strong></td></tr>
<tr><td align="right"><span dir="ltr">SB-5</span></td><td align="right">متوسط</td><td align="right">پورت‌های <span dir="ltr">SOCKS</span> محلی جلسه روی <span dir="ltr">127.0.0.1</span> (موتور، <span dir="ltr">Xray</span>، جلوی <span dir="ltr">Tor</span>/سایفون) رمز ندارند؛ برنامهٔ دیگری روی همان گوشی، به‌خصوص برنامه‌ای که با تونل‌سازی جزئی بیرون گذاشته شده، می‌تواند از آن‌ها استفاده کند</td><td align="right">باز (مستند شد)</td></tr>
<tr><td align="right"><span dir="ltr">SB-6</span></td><td align="right">کم</td><td align="right">کاربر می‌تواند محافظت نشت <span dir="ltr">IPv6</span> را در حالت‌های غیرزنجیره‌ای خاموش کند؛ در آن صورت <span dir="ltr">IPv6</span> از تونل بیرون می‌ماند</td><td align="right">پذیرفته‌شده (پیش‌فرض روشن، در زنجیره‌ها اجباری)</td></tr>
<tr><td align="right"><span dir="ltr">SB-7</span></td><td align="right">کم</td><td align="right">حالت پراکسی و برنامه‌های مستثنا در تونل‌سازی جزئی، طبق طراحی از تونل رد نمی‌شوند (از جمله <span dir="ltr">WebRTC</span>)</td><td align="right">پذیرفته‌شده (طبق طراحی)</td></tr>
<tr><td align="right"><span dir="ltr">SB-8</span></td><td align="right">کم</td><td align="right">کانفیگ ساخته‌شدهٔ <span dir="ltr">Xray</span> در طول جلسه به‌صورت متن ساده در حافظهٔ خصوصی برنامه نوشته می‌شود</td><td align="right">پذیرفته‌شده (خصوصی، پس از قطع حذف می‌شود)</td></tr>
<tr><td align="right"><span dir="ltr">SB-9</span></td><td align="right">اطلاعاتی</td><td align="right">توضیح کد <span dir="ltr">setBlocking(true)</span> آن را کیل‌سوییچ می‌نامد؛ کیل‌سوییچ واقعی <span dir="ltr">TUN</span>ِ قفل (<span dir="ltr">lockdown</span>) است</td><td align="right">اطلاعاتی</td></tr>
<tr><td align="right"><span dir="ltr">SA-1</span></td><td align="right">متوسط</td><td align="right">اشتراک روی شبکهٔ محلی به‌طور پیش‌فرض رمز ندارد (از <span dir="ltr">1.4.0</span>)</td><td align="right">باز، طبق تصمیم محصول</td></tr>
<tr><td align="right"><span dir="ltr">SA-3/4</span></td><td align="right">کم</td><td align="right">رمز پراکسی اشتراک روی شبکهٔ محلی بدون رمزنگاری؛ <span dir="ltr">WebRTC</span> مهمان روی هات‌اسپات</td><td align="right">پذیرفته‌شده / کاهش‌یافته</td></tr>
<tr><td align="right"><span dir="ltr">SA-5</span></td><td align="right">کم</td><td align="right"><span dir="ltr">R8</span> در بیلد نهایی خاموش است</td><td align="right">باز (عمدی)</td></tr>
<tr><td align="right"><span dir="ltr">SA-6</span></td><td align="right">کم</td><td align="right"><span dir="ltr">dtolnay/rust-toolchain@stable</span> یک شاخهٔ متحرک است</td><td align="right">باز</td></tr>
<tr><td align="right"><span dir="ltr">SA-7</span></td><td align="right">اطلاعاتی</td><td align="right">پین گواهی ندارد؛ فقط <span dir="ltr">CA</span>های سیستم پذیرفته می‌شوند</td><td align="right">پذیرفته‌شده</td></tr>
<tr><td align="right"><span dir="ltr">SA-8</span></td><td align="right">اطلاعاتی</td><td align="right">امضای برنامه خارج از دامنهٔ ممیزی است</td><td align="right">امتیازدهی نشده</td></tr>
</tbody>
</table>

<p dir="rtl" align="right">&nbsp;</p>

<h3 dir="rtl" align="right">‏۳. علت ریشه‌ای خطای پینگ و روش جدید (<span dir="ltr">SB-1</span>)</h3>

<p dir="rtl" align="right">‏تست قبلی سرور را با <span dir="ltr">DNS</span>ِ شبکه (که ممکن است مسموم باشد) پیدا می‌کرد، یک اتصال <span dir="ltr">TCP</span> می‌زد و بعد با <span dir="ltr">TLS</span>ِ خود اندروید دست‌دهی می‌کرد. این کار با کاری که کانفیگ واقعاً انجام می‌دهد فرق دارد: سرور <span dir="ltr">REALITY</span> فقط برای کلاینتی که کلید عمومی و <span dir="ltr">short ID</span>ِ کانفیگ را دارد دست‌دهی را کامل می‌کند و بقیه را به سایت پوششی می‌فرستد یا معطل نگه می‌دارد؛ اثر انگشت <span dir="ltr">uTLS</span>ِ کانفیگ (<span dir="ltr">firefox</span>، <span dir="ltr">chrome</span> و…) هم ارسال نمی‌شد؛ و هر خطای ناشناخته «قطع اتصال» یعنی «فیلترشده» حساب می‌شد. نتیجه: کانفیگی که در چند ثانیه وصل می‌شد، <span dir="ltr">-1</span> نشان داده می‌شد. نتیجه‌ها هم در لاگ ثبت نمی‌شد.</p>

<p dir="rtl" align="right">‏حالا تأخیر <strong>از داخل خود کانفیگ</strong> اندازه گرفته می‌شود، مثل <span dir="ltr">v2rayNG</span> و <span dir="ltr">Npvt</span>: برای هر دسته (حداکثر ۳۲ کانفیگ) یک پروسهٔ <span dir="ltr">Xray-core</span> اجرا می‌شود که برای هر کانفیگ یک ورودی <span dir="ltr">SOCKS</span> روی <span dir="ltr">loopback</span> (با نام کاربری و رمز تصادفی) دارد و به خروجی واقعی همان کانفیگ وصل است (همان ترنسپورت، <span dir="ltr">TLS/REALITY</span>، اثر انگشت، <span dir="ltr">flow</span> و <span dir="ltr">DNS</span>). سپس درخواست <span dir="ltr">GET /generate_204</span> به <span dir="ltr">www.gstatic.com</span> (و در صورت خطا <span dir="ltr">cp.cloudflare.com</span>) از آن عبور داده و زمان رفت‌وبرگشت اندازه‌گیری می‌شود؛ دو بار، و سریع‌ترین گزارش می‌شود. فقط اگر این تست شکست بخورد، یک بررسی ساده‌ی <span dir="ltr">TCP</span> علت را مشخص می‌کند: «پاسخ نمی‌دهد» (پورت باز است ولی پروتکل جواب نمی‌دهد)، «سرور خاموش» یا «فیلترشده». کانفیگی که <span dir="ltr">Xray</span> آن را نپذیرد «کانفیگ نامعتبر» نشان داده می‌شود. <span dir="ltr">TrustManager</span>ِ «همه را بپذیر» کاملاً حذف شد.</p>

<h3 dir="rtl" align="right">‏۴. نشت <span dir="ltr">DNS</span>، <span dir="ltr">WebRTC</span> و <span dir="ltr">IPv6</span></h3>

<ul dir="rtl" align="right">
<li align="right"><strong><span dir="ltr">DNS</span>:</strong> سرورهای <span dir="ltr">DNS</span>ِ تونل (<span dir="ltr">1.1.1.1</span> و <span dir="ltr">8.8.8.8</span>) داخل <span dir="ltr">TUN</span> مسیریابی می‌شوند؛ در حالت‌های <span dir="ltr">Tor</span>، <span dir="ltr">DNS</span> روی <span dir="ltr">TCP</span> از <span dir="ltr">Tor</span> می‌گذرد و <span dir="ltr">UDP</span> دور ریخته می‌شود. مسیری که پرس‌وجوی یک برنامهٔ داخل تونل به <span dir="ltr">DNS</span>ِ اپراتور برسد پیدا نشد.</li>
<li align="right"><strong><span dir="ltr">IPv6</span>:</strong> با روشن بودن محافظت نشت <span dir="ltr">IPv6</span> (پیش‌فرض) و همیشه در زنجیره‌ها و کانفیگ سفارشی، مسیر <span dir="ltr">::/0</span> داخل تونل است و <span dir="ltr">IPv6</span> نمی‌تواند تونل را دور بزند.</li>
<li align="right"><strong><span dir="ltr">WebRTC</span>:</strong> درخواست‌های <span dir="ltr">STUN</span> (<span dir="ltr">UDP</span>) داخل <span dir="ltr">TUN</span> هستند و از خروجی تونل بیرون می‌روند (در حالت <span dir="ltr">Tor</span> دور ریخته می‌شوند)؛ پس تست <span dir="ltr">WebRTC</span> آی‌پی خروجی را نشان می‌دهد نه آی‌پی واقعی را. این برای حالت پراکسی و برنامه‌های مستثنا صادق نیست (<span dir="ltr">SB-7</span>).</li>
<li align="right"><strong>کیل‌سوییچ:</strong> با خطای موتور یک <span dir="ltr">TUN</span>ِ قفل نگه داشته می‌شود که ترافیک را مسدود می‌کند و هنگام راه‌اندازی دوبارهٔ موتور هم <span dir="ltr">TUN</span> باز می‌ماند. گزینهٔ <strong>«VPN همیشه روشن» + «مسدود کردن اتصال بدون VPN»</strong> خود اندروید همچنان قوی‌ترین تضمین است و توصیه می‌شود.</li>
</ul>

<h3 dir="rtl" align="right">‏۵. موارد سالم</h3>

<ul dir="rtl" align="right">
<li align="right"><span dir="ltr">allowBackup="false"</span>، منع <span dir="ltr">cleartext</span> در کل برنامه، فقط <span dir="ltr">CA</span>های سیستم.</li>
<li align="right">فقط صفحهٔ اصلی و کاشی تنظیمات سریع <span dir="ltr">exported</span> هستند؛ سرویس <span dir="ltr">VPN</span> پشت <span dir="ltr">BIND_VPN_SERVICE</span> است؛ همهٔ <span dir="ltr">PendingIntent</span>ها <span dir="ltr">FLAG_IMMUTABLE</span> هستند.</li>
<li align="right">کانفیگ‌ها و اسرار با <span dir="ltr">AES-256-GCM</span> و کلید <span dir="ltr">Android Keystore</span> ذخیره می‌شوند؛ قفل‌های <span dir="ltr">NPVT</span> (سرور پنهان، رمز، انقضا، فقط اینترنت همراه، وابستگی به دستگاه) اعمال می‌شوند؛ <span dir="ltr">dialerProxy</span>، <span dir="ltr">mark</span> و <span dir="ltr">interface</span> از کانفیگ‌های وارداتی حذف می‌شوند.</li>
<li align="right"><span dir="ltr">FLAG_SECURE</span> روی صفحه‌های حساس؛ اسرار و لینک کانفیگ در کلیپ‌بورد حساس علامت می‌خورند.</li>
<li align="right">فایل <span dir="ltr">Xray</span> که <span dir="ltr">CI</span> دانلود می‌کند با <span dir="ltr">SHA-256</span>ِ ثابت بررسی می‌شود و <span dir="ltr">APK</span>ِ بدون <span dir="ltr">libxray.so</span> رد می‌شود.</li>
</ul>

<h3 dir="rtl" align="right">‏۶. آنچه باید روی گوشی بررسی شود</h3>

<p dir="rtl" align="right">‏ضبط بستهٔ یک جلسهٔ واقعی روی گوشی (<span dir="ltr">DNS</span>، <span dir="ltr">IPv6</span>، <span dir="ltr">WebRTC</span> و کیل‌سوییچ هنگام راه‌اندازی دوبارهٔ موتور) در این ممیزی ممکن نبود و قدم بعدی پیشنهادی است. تست تأخیر روی نمونهٔ <span dir="ltr">REALITY</span>ِ کاربر (<span dir="ltr">31.77.13.19:2063</span>) از محیط ممیزی که ترافیک خروجی‌اش فیلتر است تأیید نشد و باید روی گوشی بررسی شود.</p>

</div>
