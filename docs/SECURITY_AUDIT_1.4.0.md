# Security audit — Aether 1.4.0

**Score: 92 / 100** (91.96, weighted over ten areas).
Release under review: **1.4.0 (version code 16), core 2.1.0**. Signing is unchanged
from 1.3.0, so it installs over the current app without an uninstall.

**Target of review:** the 1.4.0 tree: Kotlin app (`app/src`), `AndroidManifest.xml`,
`res/xml` (network security, backup, data extraction), Gradle build, the release
workflow, and the LAN share (`core/ShareBridge.kt`, `core/ShareLeakGuard.kt`,
`core/SharePages.kt`, `core/LanGuard.kt`).
**Method:** manual review of the security-relevant paths plus mechanical scans
(custom `TrustManager` / `HostnameVerifier`, weak cipher and digest constructors,
cleartext policy, exported components, `PendingIntent` mutability, `FLAG_SECURE`,
sensitive clipboard, Logcat sinks, CI action pinning).
**Scope of evidence:** static review. The findings come from reading the code, not
from a dynamic test on a device.

> **Not comparable with 1.3.0's 93.** This audit adds a tenth area, the LAN share
> surface, because 1.4.0 rewrote sharing (hotspot/USB/Bluetooth binding,
> optional authentication, the `aether.check` host, the WebRTC pages). The other
> nine areas were rebalanced to make room for it. App signing stays out of scope
> by product decision, exactly as in the 1.3.0 audit.

---

## 1. Scores

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


Area 4 includes the share-page hardening shipped in 1.4.0 (see SA-2). Area 10 is lower than in 1.3.0 because
`dtolnay/rust-toolchain@stable` tracks a branch, not a release.

## 2. Findings

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


### SA-1 — LAN sharing is open by default (Medium, open)

`ShareBridge` admits any peer on the bound share subnet; authentication
(`setAuthRequired`) is off by default. At home that is the intended trade-off. On
a café or campus Wi-Fi it means strangers can send traffic through the user's
tunnel. The bridge already refuses non-public destinations, strips identity
headers, compares secrets in constant time (`LanGuard.secretEquals`), caps live
connections at 128 and rate-limits refusal logging, so the exposure is abuse of
the tunnel, not access to the phone. The setup page says whether
authentication is on and, when it is off, tells the user to enable it on public
Wi-Fi. Changing the default is a product decision and was not made here.

### SA-2 — Share pages lacked browser-side hardening (Low, fixed in 1.4.0)

The setup and check pages are plain HTTP answered to any device on the subnet.
Before the fix they had no CSP, the phone address was interpolated as-is, and the
WebRTC result was assembled with `innerHTML` from ICE candidate text. None of
these was exploitable with the current inputs (the address comes from
`InetAddress`, the exit IP is already reduced to its leading IP literal), but all
three relied on the input staying clean. Now:

- `Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline';
  script-src 'unsafe-inline'; img-src data:; base-uri 'none'; form-action 'none'`
  (meta), plus `referrer: no-referrer`. The pages load nothing from the network;
  the font is `local()` only.
- every value that does not come from `SharePages.kt` goes through `esc()`;
- the WebRTC result escapes every address before it is written.

A unit test (`ShareLeakGuardTest`) feeds a hostile host string and
asserts it comes back escaped.

### SA-3 — Clear-text proxy credentials on the LAN (Low, accepted)

HTTP `Proxy-Authorization: Basic` and SOCKS5 RFC 1929 both send the password in
clear. There is no widely supported alternative for a LAN proxy that PCs and TVs
can use. Exposure is limited to the share subnet, and the password is stored
sealed on the phone and never appears in a page.

### SA-4 — Guest WebRTC on a hotspot (Low, mitigated)

On a hotspot, browser STUN goes out through Android's
tethering NAT, which an unrooted app cannot filter. The pages run a live test
against the tunnel exit and serve one-click locks for Chrome, Edge, Brave,
Chromium and Firefox, and explain the difference between home Wi-Fi (STUN goes
straight to the router) and hotspot (STUN goes through the phone's NAT) in both
languages.

### SA-5 .. SA-8

Carried over from the 1.3.0 audit and unchanged: R8 is off on purpose, CI actions
are tag-pinned, no certificate pinning (system CAs only, user CAs refused by
`network_security_config.xml`), signing out of scope.

## 3. Verified and holding

- `android:allowBackup="false"`, cleartext denied app-wide, system trust anchors only.
- Only `MainActivity` (launcher) and the Quick Settings tile (system-permission
  guarded) are exported; the VPN service is `exported="false"` behind
  `BIND_VPN_SERVICE`; no content provider.
- Every `PendingIntent` is `FLAG_IMMUTABLE`.
- No custom `TrustManager`; every hand-rolled TLS probe calls the platform
  `HostnameVerifier`.
- Engine stdout reaches Logcat only in debug builds (`BuildConfig.DEBUG`).
- `FLAG_SECURE` on the Zero Trust sign-in, the privacy-guarded screens and the
  crash report; copied secrets are marked sensitive (`EXTRA_IS_SENSITIVE`).
- Zero Trust sign-in token: never persisted in the profile codec, sealed at rest,
  redacted in `toString`.

---

<div dir="rtl" align="right">

<h2 dir="rtl" align="right">‏خلاصهٔ فارسی</h2>

<p dir="rtl" align="right">‏امتیاز این ممیزی <strong>۹۲ از ۱۰۰</strong> شد (۹۱٫۹۶). نسخهٔ بررسی‌شده <span dir="ltr">1.4.0 (16)</span> با هستهٔ <span dir="ltr">2.1.0</span> ـه و امضا نسبت به ۱.۳.۰ عوض نشده، پس بدون حذف روی نسخهٔ فعلی نصب می‌شه. این بار یه حوزهٔ دهم هم اضافه شد: «سطح اشتراک روی شبکهٔ محلی»، چون تو نسخهٔ <span dir="ltr">1.4.0</span> بخش اشتراک کلاً بازنویسی شده. ممیزی با بررسی ایستای کد انجام شده، نه آزمون پویا روی گوشی.</p>

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
