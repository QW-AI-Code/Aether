<div dir="ltr" align="left">

## Aether 1.6.0

App version **1.6.0** · version code **19** (split codes 19001 ARMv7, 19002 ARM64, 19003 universal, 19004 x86_64) · engine core **2.4.0**.
Installs over the previous Aether when signed with the same key (the bundled public CI test key is used by default).

### What is new compared with 1.5.0

**Custom configs, tabs and import**
- **One import flow** for links and files, including hosted config lists (stored encrypted, refreshed atomically; a failed download or parse keeps the previous data).
- **Named tabs:** *All*, automatic *V2Ray* / *Npvt* tabs and your own tabs, with counters, rename and move. Deleting a tab never deletes its configs.
- **New table:** ping and status next to the name, a menu on every server, visible scrollbar, successful pings sorted ascending, green / amber / red ping colours.
- **Settings layout:** the *Highest latency first* switch moved from the bottom of the page to directly under *Health-check interval*, in the same card and style as the other switches.
- **NPVS v5** authenticated envelopes (app key or passphrase, P-256 signatures, ChaCha20-Poly1305, HKDF, author expiry and mobile-only rules) next to NPVT1. Device-bound flows, other NPVS generations and work factors above 2 million are refused.
- **Open files from other apps:** a separate import screen validates local `content` / `file` URIs, asks for a password and destination tab, and needs your confirmation. It never starts a VPN.
- **Certificate pinning:** a separate leaf-pinning Xray executable (from pinned v25.12.8 sources); a missing optional binary fails closed.

**Connection**
- **Auto-select** in the running VPN service (1 / 3 / 5 / 10 / 30 / 60 min), fresh successful results only, 50 ms / 20 % hysteresis; switching reconnects briefly.
- **Real delay** against the v2rayNG default HTTPS 204 target with certificate validation; error, redirect and captive-portal answers are refused.
- **Custom readiness** is a small end-to-end HTTPS request (12 s budget, two attempts). A default-on **connection verification** switch covers the six ordinary modes, independent of the custom gate.
- **Config → Aether / Aether → Config** repaired: TCP-aware carrier, actual exit-port publication, SNI-safe routing; exit IP is read from the real SOCKS endpoint.
- **Speed test, presets, connection sounds** and the engine core 2.4.0 options (*Verify gateway certificate*, *WARP API address*).
- **Gemini assistant** uses the active session's final endpoint and never receives the private config vault; contextual help icons beside settings.

**Profiles (save, import and export)**
- **Profile management.** Save your current settings as a new profile with one tap, switch between profiles whenever you like, import settings from a file, and export the selected profile in a non-confidential form to share it with friends. The exported file leaves out your sensitive data, so you can hand it over without worrying about leaking private information.

**Clean-IP scan**
- **New Clean-IP scan.** The app now finds a clean IP by itself; no separate IP-scanner app is needed. For configs hosted on Cloudflare (for example BPB panels), when a V2Ray server's ping rises or the server stops working, tap the magnifier icon, or open the three-dot menu and choose *Clean IP*, next to that server to start a scan. Once a clean IP and port are found, the app replaces the address automatically and updates the server. The *Scan clean IPs of this tab* button scans every server of the tab one after another. **Important:** scan speed depends on your network. Always scan with the VPN turned off. If a message says no suitable IP was found, repeat the scan a few more times. In the *Clean IP* settings you can change the scan depth or enter your own IP range.
- **The Clean-IP result card ("No stable real-proxy candidate qualified; the current endpoint was kept.", and the other scan results) no longer closes by itself.** It stays until you tap the X.

**Behaviour and polish**
- **Display and sound switches:** *Home screen animations* turns off the blue glow around the connect button and other moving effects on weaker phones; *Connection sounds* turns the short connect / disconnect cue on or off (silent mode is respected).
- **What's new dialog** updated with the 1.6.0 list; the onboarding pager was removed.
- Stable-key tab strip fixes the stale tab-index crash.
- Automatic core update from GitHub was removed; `scripts/sync-core.sh` is an inert stub.
- Complete Gradle Wrapper with distribution checksum; CI runs debug and release unit tests before packaging.

### Security audit (1.6.0) - full 0-100 static audit

**Result: 76 / 100** (static review). This is a complete 0-100 audit of the 1.6.0 source tree, scored on eight weighted areas (weights add up to 100). Method: manifest and resource review, scans for trust managers, cleartext, hard-coded secrets, WebView, process execution and logging, review of VPN routing, key storage, `build.gradle.kts`, `Cargo.lock` and the CI workflow. **Not done:** no dynamic test or packet capture on a phone, no live `cargo audit` or Gradle dependency scan (no network in the review sandbox), no Android/NDK build. The method is stricter and more granular than the 1.5.0 review (92 / 100), so the two numbers are **not directly comparable**; no vulnerability that is exploitable remotely was found.

| # | Area | Max | Score | Finding |
| --- | --- | --- | --- | --- |
| 1 | Data leakage (DNS, IPv6, kill switch) | 15 | 13 | Code review of `AetherVpnService`: `0.0.0.0/0` and `::/0` are both routed into the TUN, DNS servers are set inside the tunnel, blocking TUN is kept for the kill switch. Proxy mode and split-tunnel-excluded apps bypass the tunnel by design. Not measured on a phone (no DNS/IPv6 leak test run). |
| 2 | Android permissions & components | 15 | 13 | Only INTERNET, ACCESS_NETWORK_STATE, FOREGROUND_SERVICE(+SPECIAL_USE), POST_NOTIFICATIONS, CAMERA (QR, optional hardware). No install-packages, no all-packages query, no WebView, `allowBackup=false`. Exported: launcher, QS tile (system permission), `ConfigImportActivity` with a broad `*/*` filter (-2). |
| 3 | TLS & cryptography | 15 | 11 | `usesCleartextTraffic=false`, system trust anchors only, hostname verification in the probes. AES-256-GCM secrets in Android Keystore, NPVS v5 with P-256, ChaCha20-Poly1305, HKDF. Deduction (-4): `CleanIpScanner` uses a trust-all `X509TrustManager` to probe candidate IPs with arbitrary SNI; results are only applied after a real-proxy ping. |
| 4 | Dependency security | 15 | 11 | OkHttp 4.12.0, BouncyCastle 1.80, zxing 3.5.3, Gradle Wrapper checksum, strict `cargo audit` in CI. Deductions (-4): the committed `Cargo.lock` still has `quinn-proto` 0.11.17 and `rustls` 0.23.43 and is raised to 0.11.18 / 0.23.46 only by `scripts/patch-engine-dependencies.py` during CI; Compose BOM 2024.10.01 is old. `cargo audit` could not be run offline for this review. |
| 5 | Key management | 15 | 9 | Runtime keys are generated in the Android Keystore, non-exportable, and no hard-coded API keys or tokens were found by a secret scan. Deduction (-6): the CI signing keystore is stored in the repository (`.github/ci-keystore.jks.b64`); it guarantees updatability but not authenticity. Move it to GitHub Secrets when possible. |
| 6 | Local data, backup & logging | 10 | 9 | Backup and data extraction disabled with explicit rules, configs and logs encrypted at rest, locked server data kept out of logs. Deduction (-1): 146 `Log.d/v/i` calls remain; they carry no secrets in the reviewed paths but were not audited one by one. |
| 7 | Supply chain & CI | 10 | 7 | Pinned Xray sources, signer-fingerprint guards, strict preflight tests. Deductions (-3): GitHub Actions are referenced by version tag, not commit SHA; R8/minify is off. |
| 8 | Local services | 5 | 3 | Chain SOCKS ports and LAN sharing are bound deliberately, but have no password by default (-2). |
| | **Total** | **100** | **76** | |

Open items, by priority: (1) move the CI signing key to GitHub Secrets, (2) pin Actions to commit SHAs, (3) bump the Compose BOM and refresh `Cargo.lock`, (4) restrict the Clean-IP trust-all context further, (5) optional password for local SOCKS/LAN sharing.

Details: `docs/SECURITY_AUDIT_1.5.0.md`, `docs/SECURITY_CI_FIX_1.6.0.md`, `docs/AUTO_SIGNING_1.6.0.md`, `docs/RELEASE_1.6.0.md`.

</div>

<div dir="rtl" align="right">

## ‏Aether نسخهٔ 1.6.0

‏نسخهٔ برنامه <span dir="ltr">**1.6.0**</span> · کد نسخه <span dir="ltr">**19**</span> (کدهای تفکیکی: <span dir="ltr">19001</span> ARMv7، <span dir="ltr">19002</span> ARM64، <span dir="ltr">19003</span> universal، <span dir="ltr">19004</span> x86_64) · هستهٔ موتور <span dir="ltr">**2.4.0**</span>.
در صورت امضا با همان کلید، روی نسخهٔ قبلی نصب می‌شود (به‌طور پیش‌فرض از همان کلید عمومی تستی CI استفاده می‌شود).

### تازه‌ها نسبت به نسخهٔ 1.5.0

**کانفیگ‌های سفارشی، تب‌ها و ورود**
- **یک مسیر واحد ورود** برای لینک و فایل، شامل لینک کانفیگ میزبانی‌شده (رمزنگاری‌شده ذخیره و به‌صورت اتمی به‌روز می‌شود؛ با خطای دانلود یا پارس، داده‌های قبلی می‌مانند).
- **تب‌های نام‌دار:** «همه»، تب‌های خودکار <span dir="ltr">V2Ray</span> و <span dir="ltr">Npvt</span> و تب‌های دلخواه، با شمارنده، تغییر نام و انتقال. حذف تب هرگز کانفیگ‌های آن را حذف نمی‌کند.
- **جدول تازه:** پینگ و وضعیت کنار نام، منوی هر سرور، نوار اسکرول نمایان، مرتب‌سازی صعودی پینگ‌های موفق و رنگ سبز / زرد / قرمز پینگ.
- **چیدمان تنظیمات:** گزینهٔ «اول بیشترین تأخیر» از پایین صفحه به درست زیر «بازهٔ پایش سلامت» منتقل شد، با همان کارت و استایل سایر گزینه‌ها.
- **پاکت‌های احرازاصالت‌شدهٔ <span dir="ltr">NPVS v5</span>** (باز کردن با کلید برنامه یا گذرواژه، امضای <span dir="ltr">P-256</span>، <span dir="ltr">ChaCha20-Poly1305</span>، <span dir="ltr">HKDF</span>، انقضا و «فقط اینترنت همراه») در کنار <span dir="ltr">NPVT1</span>. جریان‌های وابسته به دستگاه، نسل‌های دیگر <span dir="ltr">NPVS</span> و ضریب کار بیش از دو میلیون رد می‌شوند.
- **بازکردن فایل از برنامه‌های دیگر:** صفحهٔ ورود جداگانه، <span dir="ltr">URI</span>های محلی را اعتبارسنجی می‌کند، گذرواژه و تب مقصد را می‌پرسد و پیش از ذخیره تأیید می‌گیرد؛ هرگز <span dir="ltr">VPN</span> را روشن نمی‌کند.
- **پین گواهی:** فایل اجرایی جداگانهٔ <span dir="ltr">Xray</span> با پین گواهی <span dir="ltr">leaf</span> (از سورس تثبیت‌شدهٔ <span dir="ltr">v25.12.8</span>)؛ نبودن فایل اختیاری باعث توقف امن می‌شود.

**اتصال**
- **انتخاب خودکار** در سرویس <span dir="ltr">VPN</span> فعال (۱، ۳، ۵، ۱۰، ۳۰ و ۶۰ دقیقه)، فقط نتایج موفق و تازه، با آستانهٔ ۵۰ میلی‌ثانیه و ۲۰ درصد؛ تعویض با وقفهٔ کوتاه همراه است.
- **تأخیر واقعی** با مقصد <span dir="ltr">HTTPS 204</span> پیش‌فرض <span dir="ltr">v2rayNG</span> و اعتبارسنجی گواهی؛ پاسخ خطا، تغییر مسیر و <span dir="ltr">captive portal</span> رد می‌شوند.
- **آماده‌بودن اتصال سفارشی** با یک درخواست کوچک <span dir="ltr">HTTPS</span> (سقف ۱۲ ثانیه، دو تلاش). گزینهٔ پیش‌فرض‌روشنِ **راستی‌آزمایی اتصال** شش حالت معمول را مستقل از مرحلهٔ سفارشی پوشش می‌دهد.
- **اصلاح <span dir="ltr">Config → Aether</span> و <span dir="ltr">Aether → Config</span>:** سیاست آگاه از <span dir="ltr">TCP</span>، انتشار پورت خروجی واقعی، مسیریابی سازگار با <span dir="ltr">SNI</span>؛ <span dir="ltr">IP</span> خروجی از <span dir="ltr">SOCKS</span> واقعی خوانده می‌شود.
- **تست سرعت، پریست‌ها، صدای اتصال** و گزینه‌های هستهٔ ۲٫۴٫۰ (<span dir="ltr">Verify gateway certificate</span> و <span dir="ltr">WARP API address</span>).
- **دستیار <span dir="ltr">Gemini</span>** از پورت خروجی واقعی نشست استفاده می‌کند و گاوصندوق خصوصی کانفیگ را دریافت نمی‌کند؛ آیکون‌های راهنمای هوشمند کنار تنظیمات.

**مدیریت پروفایل‌ها (ذخیره، ورود و خروجی)**
- حالا می‌توانید تنظیمات فعلی خود را با یک ضربه به‌عنوان «پروفایل جدید» ذخیره کنید و هر زمان خواستید میان پروفایل‌ها جابه‌جا شوید. تنظیمات را از یک فایل وارد کنید، یا پروفایل انتخاب‌شده را به‌صورت «غیرمحرمانه» خروجی بگیرید و با دوستانتان به اشتراک بگذارید. در این خروجی اطلاعات حساس شما همراه نمی‌شود؛ پس می‌توانید بی‌نگرانی از لو رفتن اطلاعات خصوصی آن را دست‌به‌دست کنید.

**اسکن آی‌پی تمیز**
- قابلیت جدید اسکن آی‌پی تمیز به برنامه اضافه شد. دیگر نیازی به برنامه‌های جانبی اسکنر <span dir="ltr">IP</span> نیست و همه چیز به صورت خودکار انجام می‌شود. برای کانفیگ‌هایی که روی بستر کلودفلر (مثل پنل <span dir="ltr">BPB</span> و غیره) هستند، اگر پینگ سرور <span dir="ltr">V2Ray</span> بالا رفت یا از کار افتاد، می‌توانید با زدن آیکون ذره‌بین یا از طریق منوی سه‌نقطه و انتخاب «آی‌پی تمیز» در کنار هر سرور، اسکن را آغاز کنید. برنامه پس از پیدا کردن آی‌پی و پورت تمیز، به صورت خودکار آن را جایگزین کرده و به سرور اضافه می‌کند. همچنین با زدن دکمه «اسکن آی‌پی تمیز»، اسکن به ترتیب برای تمام سرورهای آن تب انجام می‌شود. **نکته مهم:** بسته به شرایط شبکه، سرعت اسکن متغیر است. اسکن باید حتماً در حالت <span dir="ltr">VPN</span> خاموش انجام شود. اگر پیامی مبنی بر پیدا نشدن <span dir="ltr">IP</span> مناسب نمایش داده شد، کاربر باید چند بار دیگر اسکن را تکرار کند. همچنین در تنظیمات بخش «آی‌پی تمیز»، کاربر می‌تواند سختی اسکن را تغییر دهد یا رنج <span dir="ltr">IP</span> دلخواه خود را وارد کند.
- **کارت نتیجهٔ اسکن آی‌پی تمیز («کاندیدای واجد شرایط با پینگ واقعی پایدار پیدا نشد؛ آدرس فعلی حفظ شد.» و سایر نتایج) دیگر خودکار بسته نمی‌شود** و تا زمانی که روی ضربدر نزنید می‌ماند.

**رفتار و جزئیات**
- **گزینه‌های نمایش و صدا:** «انیمیشن‌های صفحهٔ اصلی» نور آبی دور دکمهٔ اتصال و سایر افکت‌های متحرک را در گوشی‌های ضعیف‌تر خاموش می‌کند؛ «صدای اتصال و قطع اتصال» صدای کوتاه اتصال و قطع اتصال را روشن یا خاموش می‌کند (حالت بی‌صدا رعایت می‌شود).
- **مدال «تازه‌های نسخه»** با فهرست 1.6.0 به‌روز شد؛ صفحات معرفی اولیه حذف شدند.
- نوار تب با کلید پایدار، کرش ایندکس قدیمی تب را برطرف می‌کند.
- به‌روزرسانی خودکار هسته از <span dir="ltr">GitHub</span> حذف شد؛ <span dir="ltr">`scripts/sync-core.sh`</span> فقط یک <span dir="ltr">stub</span> غیرفعال است.
- <span dir="ltr">Gradle Wrapper</span> کامل همراه <span dir="ltr">checksum</span> توزیع؛ <span dir="ltr">CI</span> پیش از بسته‌بندی تست‌های واحد <span dir="ltr">debug</span> و <span dir="ltr">release</span> را اجرا می‌کند.

### ممیزی امنیتی (1.6.0) - ممیزی کامل ۰ تا ۱۰۰ (ایستا)

**نتیجه: <span dir="ltr">76 / 100</span>** (بررسی ایستا). این یک ممیزی کامل ۰ تا ۱۰۰ روی کل درخت سورس 1.6.0 است و بر پایهٔ هشت حوزهٔ وزن‌دار (مجموع وزن‌ها ۱۰۰) امتیاز داده شده است. روش: بررسی مانیفست و منابع، اسکن <span dir="ltr">TrustManager</span>، ترافیک بدون رمز، رازهای ثابت، <span dir="ltr">WebView</span>، اجرای فرایند و لاگ، و بررسی مسیریابی <span dir="ltr">VPN</span>، ذخیرهٔ کلید، <span dir="ltr">build.gradle.kts</span>، <span dir="ltr">Cargo.lock</span> و <span dir="ltr">workflow</span>. **انجام نشده:** آزمون پویا یا ضبط بسته روی گوشی، <span dir="ltr">cargo audit</span> زنده یا اسکن وابستگی <span dir="ltr">Gradle</span> (نبود اینترنت در محیط بررسی) و بیلد <span dir="ltr">Android/NDK</span>. این روش دقیق‌تر از بررسی 1.5.0 (<span dir="ltr">92 / 100</span>) است؛ پس دو عدد **مستقیماً قابل مقایسه نیستند**. آسیب‌پذیری قابل بهره‌برداری از راه دور پیدا نشد.

<table dir="rtl" align="right">
<thead>
<tr><th align="right">#</th><th align="right">حوزه</th><th align="right">حداکثر</th><th align="right">امتیاز</th><th align="right">یافته</th></tr>
</thead>
<tbody>
<tr><td align="right">۱</td><td align="right">نشت داده (DNS، IPv6، کیل‌سوئیچ)</td><td align="right">۱۵</td><td align="right">۱۳</td><td align="right">بررسی کد <span dir="ltr">AetherVpnService</span>: مسیرهای <span dir="ltr">0.0.0.0/0</span> و <span dir="ltr">::/0</span> هر دو به تونل می‌روند، <span dir="ltr">DNS</span> داخل تونل تنظیم می‌شود و برای کیل‌سوئیچ <span dir="ltr">TUN</span> مسدودکننده حفظ می‌شود. حالت پروکسی و برنامه‌های مستثنا در تونل تقسیمی عمداً از تونل عبور نمی‌کنند. روی گوشی اندازه‌گیری نشده است.</td></tr>
<tr><td align="right">۲</td><td align="right">مجوزهای اندروید و مؤلفه‌ها</td><td align="right">۱۵</td><td align="right">۱۳</td><td align="right">فقط مجوزهای اینترنت، وضعیت شبکه، سرویس پیش‌زمینه، اعلان و دوربین (اسکن <span dir="ltr">QR</span>، اختیاری). نصب بسته، پرس‌وجوی همهٔ برنامه‌ها و <span dir="ltr">WebView</span> وجود ندارد و <span dir="ltr">allowBackup=false</span> است. مؤلفه‌های export‌شده: اکتیویتی اصلی، کاشی تنظیمات سریع (با مجوز سیستمی) و <span dir="ltr">ConfigImportActivity</span> با فیلتر گستردهٔ <span dir="ltr">*/*</span> (۲- امتیاز).</td></tr>
<tr><td align="right">۳</td><td align="right">رمزنگاری و TLS</td><td align="right">۱۵</td><td align="right">۱۱</td><td align="right">ترافیک بدون رمز ممنوع، فقط گواهی‌های سیستم و بررسی نام میزبان در پروب‌ها. رازها با <span dir="ltr">AES-256-GCM</span> در <span dir="ltr">Android Keystore</span> و <span dir="ltr">NPVS v5</span> با <span dir="ltr">P-256</span> و <span dir="ltr">ChaCha20-Poly1305</span> و <span dir="ltr">HKDF</span>. کسر امتیاز (۴-): اسکنر آی‌پی تمیز برای پروب آی‌پی‌های کاندید با <span dir="ltr">SNI</span> دلخواه از <span dir="ltr">TrustManager</span> «همه را بپذیر» استفاده می‌کند؛ نتیجه فقط پس از پینگ واقعی پروکسی اعمال می‌شود.</td></tr>
<tr><td align="right">۴</td><td align="right">امنیت وابستگی‌ها</td><td align="right">۱۵</td><td align="right">۱۱</td><td align="right"><span dir="ltr">OkHttp 4.12.0</span>، <span dir="ltr">BouncyCastle 1.80</span>، <span dir="ltr">zxing 3.5.3</span>، <span dir="ltr">checksum</span> برای <span dir="ltr">Gradle Wrapper</span> و <span dir="ltr">cargo audit</span> سخت‌گیرانه در <span dir="ltr">CI</span>. کسر امتیاز (۴-): فایل <span dir="ltr">Cargo.lock</span> داخل مخزن هنوز <span dir="ltr">quinn-proto 0.11.17</span> و <span dir="ltr">rustls 0.23.43</span> دارد و فقط هنگام بیلد توسط <span dir="ltr">scripts/patch-engine-dependencies.py</span> به <span dir="ltr">0.11.18</span> و <span dir="ltr">0.23.46</span> ارتقا می‌یابد؛ <span dir="ltr">Compose BOM 2024.10.01</span> قدیمی است. <span dir="ltr">cargo audit</span> در این بررسی آفلاین اجرا نشد.</td></tr>
<tr><td align="right">۵</td><td align="right">مدیریت کلیدها</td><td align="right">۱۵</td><td align="right">۹</td><td align="right">کلیدهای زمان اجرا در <span dir="ltr">Android Keystore</span> و غیرقابل‌استخراج ساخته می‌شوند و اسکن رمز، کلید یا توکن ثابت در کد پیدا نکرد. کسر امتیاز (۶-): کلید امضای <span dir="ltr">CI</span> داخل مخزن است (<span dir="ltr">.github/ci-keystore.jks.b64</span>)؛ به‌روزرسانی‌پذیری را تضمین می‌کند ولی اصالت را نه. در صورت امکان به <span dir="ltr">GitHub Secrets</span> منتقل شود.</td></tr>
<tr><td align="right">۶</td><td align="right">داده محلی، پشتیبان و لاگ</td><td align="right">۱۰</td><td align="right">۹</td><td align="right">پشتیبان‌گیری و استخراج داده با قوانین صریح غیرفعال است، کانفیگ‌ها و لاگ‌ها رمزشده ذخیره می‌شوند و داده‌های سرور قفل‌شده وارد لاگ نمی‌شوند. کسر امتیاز (۱-): ۱۴۶ فراخوانی <span dir="ltr">Log.d/v/i</span> باقی مانده است؛ در مسیرهای بررسی‌شده رازی ندارند ولی تک‌تک ممیزی نشدند.</td></tr>
<tr><td align="right">۷</td><td align="right">زنجیرهٔ تأمین و CI</td><td align="right">۱۰</td><td align="right">۷</td><td align="right">سورس‌های <span dir="ltr">Xray</span> تثبیت‌شده، نگهبان اثر انگشت امضاکننده و تست‌های پیش‌پرواز سخت‌گیرانه. کسر امتیاز (۳-): اکشن‌های <span dir="ltr">GitHub</span> با برچسب نسخه و نه <span dir="ltr">SHA</span> کامیت ارجاع می‌شوند؛ <span dir="ltr">R8</span> خاموش است.</td></tr>
<tr><td align="right">۸</td><td align="right">سرویس‌های محلی</td><td align="right">۵</td><td align="right">۳</td><td align="right">پورت‌های <span dir="ltr">SOCKS</span> زنجیره و اشتراک‌گذاری شبکهٔ محلی عمداً باز می‌شوند ولی به‌طور پیش‌فرض رمز ندارند (۲- امتیاز).</td></tr>
<tr><td align="right"></td><td align="right"><b>مجموع</b></td><td align="right"><b>۱۰۰</b></td><td align="right"><b>۷۶</b></td><td align="right"></td></tr>
</tbody>
</table>

موارد باز به ترتیب اولویت: (۱) انتقال کلید امضای <span dir="ltr">CI</span> به <span dir="ltr">GitHub Secrets</span>، (۲) ثابت‌کردن اکشن‌ها با <span dir="ltr">SHA</span> کامیت، (۳) ارتقای <span dir="ltr">Compose BOM</span> و تازه‌سازی <span dir="ltr">Cargo.lock</span>، (۴) محدودترکردن زمینهٔ «همه را بپذیر» در اسکنر آی‌پی تمیز، (۵) رمز اختیاری برای <span dir="ltr">SOCKS</span> محلی و اشتراک شبکه.

‏جزئیات: <span dir="ltr">`docs/SECURITY_AUDIT_1.5.0.md`</span>، <span dir="ltr">`docs/SECURITY_CI_FIX_1.6.0.md`</span>، <span dir="ltr">`docs/AUTO_SIGNING_1.6.0.md`</span>، <span dir="ltr">`docs/RELEASE_1.6.0.fa.md`</span>.

</div>
