<div dir="ltr" align="left">

## Aether 1.7.0

App version **1.7.0** · version code **20** (split codes 20001 ARMv7, 20002 ARM64, 20003 universal, 20004 x86_64) · engine core **2.4.0**.
Installs over the previous Aether when signed with the same key (the bundled public CI test key is used by default).

### Latest same-version update (1.7.0 / version code 20): Smart anti-DPI brain and Smart connection gate

The version number, version code and signing key are unchanged, so this build installs over
the previous 1.7.0.

#### Smart anti-DPI brain (`core/DpiBrain.kt`)
- **Listens:** every engine output line is tagged with what it says about the network: UDP
  handshakes timing out, TLS handshakes reset or alerted, a scan that found no gateway, an
  unreachable account API, a damaged identity file, a missing ECH key.
- **Diagnoses** every failed attempt (`UDP_BLOCKED`, `TLS_INTERFERENCE`, `GATEWAYS_FILTERED`,
  `API_BLOCKED`, `IDENTITY_DAMAGED`, `ENGINE_FAULT`, ...) from those signals and from how the
  attempt ended.
- **Learns:** both race lanes share one board. Two UDP strikes, or one when the direct
  fingerprint already saw UDP/QUIC struggle, mean "UDP is blocked here". One TLS reset means
  "TLS is being interfered with".
- **Adapts** the rest of the race on the spot, with no setting needed:
  - TCP carriers (MASQUE over HTTP/2) move ahead of UDP ones (WireGuard, HTTP/3);
  - TLS ClientHello fragmentation and at least the Firewall obfuscation floor are switched on
    for TCP carriers;
  - WireGuard gets the GFW disguise when its gateways stay silent;
  - a UDP rung on a UDP-hostile network is abandoned after 7 s instead of its full 20-25 s
    budget;
  - equivalent tactics are never tried twice.
  The serial Smart ladder and the last-resort rungs adapt the same way.
- **Remembers** per network **and per operator**. The operator key is a truncated SHA-256;
  the operator name is never stored. A new network of the same operator starts with what
  worked on the others.
- **Heals** a damaged engine identity and retries the same tactic once. Two identical
  start-up faults on different protocols end the ladder early instead of failing every rung.
- Fixes in this revision:
  - an attempt the brain abandoned itself is no longer counted as new evidence, so a verdict
    cannot reinforce itself;
  - an engine line that reports a *recovered* identity is no longer read as a damaged one.

#### Smart connection gate (`core/LiveDataProof.kt`): "Verify connection" is gone everywhere
- The **Verify connection** switch is removed from Settings → Connection **and** from Custom
  configs. Its preference fields, `VerificationPolicy` and `CustomReadiness` are deleted.
- The connecting state `Verifying` is now `Proving`. The status text reads "Smart gate: waiting
  for real data…" instead of "Verifying connection…".
- Every session, built-in or custom, alone or chained, is reported **Connected only after a
  real HTTP request crossed the tunnel and its answer came back**:
  - three independent targets are raced (`1.1.1.1` by address, `www.gstatic.com`,
    `cp.cloudflare.com` by name);
  - the first answer wins, usually under a second;
  - a bare SOCKS5 reply never counts, so a fake connection cannot pass;
  - a dead engine or Xray ends the gate at once.
- Device DNS over SOCKS5 UDP is still measured for the diagnostics panel but no longer blocks
  (Android resolves over DoT/DoH when UDP is slow). That requirement is what kept MASQUE from
  connecting.
- Custom configs: the config's own listener is proven the same way before anything is chained
  to it (40 s budget, no geo-IP, no UDP requirement).
- An information card ("Smart connection gate", the same block as in the desktop app) is
  shown in Settings → Connection and in Custom configs.

#### Other changes
- **Auto-select lowest delay** for custom configs is now **on by default** (new installs; a choice you already saved is kept).
- In Persian, AI errors show only the translated sentence: Google's or the app's raw English diagnostic text is no longer appended (it stays in the diagnostics log).
- **Chained stage 1 is proven by real data too:** before stage 2 (e.g. Psiphon behind Aether) starts, stage 1 must carry a real HTTP answer (smart gate), not only accept a CONNECT. The Tor bootstrap stall detection still ends the wait early.
- Root cause of `stream did not contain valid UTF-8` fixed: the identity vault is serialised
  and never seals or restores non-UTF-8 data, each engine releases its running count once,
  and the core quarantines a damaged identity instead of exiting.
- Faster Smart comparison: 2.5 s quality window, 4.5 s measurement per route.
- What's new dialog revision `r5`.

#### Security audit (0-100) - this release

| # | Area | Weight | Score | Findings |
|---|------|:-----:|:-----:|----------|
| 1 | Secrets at rest | 15 | 14 | Engine identity sealed with the Android Keystore between sessions; vault serialised; damaged copies shredded, never sealed. `allowBackup=false`. |
| 2 | Secrets on the command line | 10 | 10 | Zero Trust credentials and upstream URLs only in the engine environment; team, peer and DNS values redacted in logs. |
| 3 | Leak protection | 15 | 14 | Kill switch on by default, IPv6 routed, direct rules suspended while LAN sharing is on, the app is excluded from its own TUN. |
| 4 | Connection-gate integrity | 10 | 9 | Smart gate requires real response bytes; bounded socket timeouts; probes carry no user data and run inside the tunnel. Plain-HTTP probe targets are public endpoints (low residual risk). |
| 5 | Smart brain and memory privacy | 5 | 5 | Pure logic; memory stores hashed network/operator keys and tactic ids only, with expiry and a 32-entry cap. |
| 6 | TLS validation | 10 | 8 | Hostname verification for Gemini and geo lookups; `usesCleartextTraffic=false`. MASQUE certificate pinning (`--tls-verify`) is opt-in. |
| 7 | Dependencies and supply chain | 15 | 13 | Strict `cargo audit` gate before and after the native build; security floors for quinn/rustls/url/cookie. One scoped, lockfile-verified exception (RUSTSEC-2026-0459). |
| 8 | Build integrity and signing | 10 | 6 | Release-tree manifest and signer fingerprint pin. Builds are signed with the bundled **public** CI test key unless private signing secrets are set. Unchanged on purpose, so updates install over the previous version. |
| 9 | Logging and diagnostics | 5 | 5 | Engine output kept out of Logcat in release builds; diagnostics report redacted. |
| 10 | Input validation | 5 | 5 | Profile, DNS, route, upstream and TLS options sanitised before they reach the engine. |
| | **Total** | **100** | **89** | **Good.** Main residual risk: the public CI signing key (authenticity, not updatability). Set your own signing secrets for public distribution. |

### What is new compared with 1.6.0

**Root cause of the connection failures - fixed**
- **The engine was killed while it was still working.** The engine binds its local SOCKS5 port at start-up and only *serves* it once a tunnel is validated. The app took the open port (~0.3 s after launch) for a ready engine and gave every attempt one 4-second handshake, so MASQUE failed "sometimes" and every gateway scan failed always. Both Tehran field logs show every attempt dying exactly 4.0 s after the port opened.
- **New readiness gate** (`EngineReadiness`): waits, for the attempt's whole budget, until the engine reports that it serves SOCKS5 and a real handshake succeeds; ends at once when the engine exits. Used by manual protocols, Smart mode, chained stage 1 and supervisor restarts.

**Smart mode, rewritten - a fair race, and the best route rather than the first**
- **Fair start:** WARP (WireGuard) and MASQUE lanes start at the same instant with the same per-tactic budgets (light 20 s, hardened 25 s, two-hop 40 s). 1.6.0 started WireGuard first, MASQUE 7 s later, after a serial 45 s "remembered route" slot.
- **Light to heavy:** bare WireGuard and MASQUE over HTTP/3 / HTTP/2 first; Noize, TLS-fragmented HTTP/2, then two-hop Gool and MASQUE×2 only when needed.
- **Best route wins:** the race runs 3.5 s past the first proof so the other lane can prove a route too; every proven route is measured through itself (latency, ~384 KB download, 128 KB upload, verified TLS) and ranked with a per-protocol overhead factor. If the best one does not come up as the session, the next proven route is tried.
- **HTTP/2 first** on mobile data, on an Iranian SIM (MCC 432, also on Wi-Fi), on UDP-throttled networks and when the new QUIC Version-Negotiation probe gets no answer.
- **Per-network memory** re-orders lanes only; failures are recorded per network. **Last resort** (deep full-range scan of the two cleanest carriers) only when nothing proved itself.

**Other fixes**
- **ECH crash:** anti-DPI passes no longer force ECH; without a fetchable ECH key the engine refused to start and every hardened MASQUE attempt died in 0.4 s. ECH is only ever the user's choice.
- **Identity clash:** core 2.4.0's Gool (WireGuard inside MASQUE) now races in the MASQUE lane, no longer concurrently with MASQUE on the same identity and gateway cache.
- **Scan ranges:** MASQUE/Gool scans are no longer narrowed to the ranges measured for WireGuard (e.g. `8.6.112.0/24`).
- **Ladder:** built from the race's tactic catalogue, families interleaved; MASQUE over HTTP/2 is no longer missing on "open" networks.
- **Manual MASQUE:** as configured → MASQUE over HTTP/2 (TCP) → anti-DPI pass (HTTP/2 + TLS fragmentation + obfuscation).
- **What's new dialog:** 1.7.0 list; the *"If you cannot connect at all"* steps are kept. Preset exports are tagged 1.7.0; 1.6.0 exports still import.
- **Connect button did not respond (same-version fix, Galaxy S24 Ultra report):** when Android refused the VPN consent without showing it (another app set as *Always-on VPN*, a work profile / Secure Folder / device policy, or a screen overlay blocking *OK*), the result was ignored and the button looked dead. The refusal is now shown on the home screen and in Diagnostics, a dialog explains it and opens the VPN settings, and a consent granted after the activity was recreated no longer loses the tap. The What's new dialog lists the fix and is shown once more.
- **Same-version improvements:** fast first connect with auto-select (connect to the first fast server, switch to the overall fastest when the full test ends); immediate switch after "Test all"; re-test after a Wi-Fi / mobile-data / SIM change; one-tap ping, speed test and delete on every config; "Speed test all" and sort by ping, download or upload; interactive connection card (latency statistics, chain nodes, per-app traffic); unified connection-chain builder in Settings → Connection; "Send diagnostics" to the developers' GitHub (redacted, user-submitted, can be turned off). New special access: `PACKAGE_USAGE_STATS` (only after the user grants "Usage access", only for the per-app traffic list).

### Security notes (1.7.0)

No new permission, component or listener. New network activity, all of it during a Smart connect only:
the quality measurement runs **through the tunnel being measured** (`1.1.1.1:80` round trips, HTTPS to `speed.cloudflare.com` with certificate and hostname verification), and the fingerprint sends one 1200-byte QUIC Version-Negotiation probe (random bytes, no identity, no server name) directly to two Cloudflare MASQUE gateways.
ECH is never switched on or off behind the user's back any more. The latest full 0-100 static audit remains the 1.6.0 one (76 / 100, see `docs/SECURITY_AUDIT_1.5.0.md`, `docs/SECURITY_CI_FIX_1.6.0.md`).

**Dependency audit (CI):** RustSec advisories published after 1.6.0 are fixed by lockfile security floors applied before the engine is compiled - `quinn-proto` 0.11.19 (RUSTSEC-2026-0523/0524), `quinn-udp` 0.5.16 (0538), `url` 2.5.9 (0592), `cookie` 0.18.3 (0594). RUSTSEC-2026-0459 (bundled SQLite, `libsqlite3-sys` 0.37.0) has no compatible fix - it needs rusqlite 0.41, which Tor's `tor-dirmgr` 0.46 does not accept - and is a scoped, documented exception: SQLite only opens Tor's directory cache in app-private storage, and the exception is applied only while the lockfile shows exactly that dependency shape. A new early audit gate runs before the native build, so a new advisory fails the job in minutes instead of after an hour.

Details: `docs/RELEASE_1.7.0.md`.

</div>

<div dir="rtl" align="right">

## ‏Aether نسخهٔ 1.7.0

‏نسخهٔ برنامه <span dir="ltr">**1.7.0**</span> · کد نسخه <span dir="ltr">**20**</span> (کدهای تفکیکی: <span dir="ltr">20001</span> ARMv7، <span dir="ltr">20002</span> ARM64، <span dir="ltr">20003</span> universal، <span dir="ltr">20004</span> x86_64) · هستهٔ موتور <span dir="ltr">**2.4.0**</span>.
در صورت امضا با همان کلید، روی نسخهٔ قبلی نصب می‌شود (به‌طور پیش‌فرض از همان کلید عمومی تستی CI استفاده می‌شود).

### تازه‌ترین به‌روزرسانی هم‌نسخه (<bdi>1.7.0</bdi> / کد نسخه ۲۰): مغز هوشمند ضد <bdi>DPI</bdi> و دروازهٔ هوشمند اتصال

شمارهٔ نسخه، کد نسخه و کلید امضا تغییر نکرده‌اند؛ پس این بیلد روی <bdi>1.7.0</bdi> قبلی نصب می‌شود.

#### مغز هوشمند ضد <bdi>DPI</bdi> (<bdi>core/DpiBrain.kt</bdi>)
- **گوش می‌دهد:** هر خط خروجی هسته از نظر وضعیت شبکه برچسب می‌خورد: سکوت دست‌دادن‌های <bdi>UDP</bdi>، قطع یا هشدار در دست‌دادن <bdi>TLS</bdi>، اسکنی که دروازه پیدا نکرد، در دسترس نبودن سرور ثبت‌نام، فایل هویت خراب و نبودن کلید <bdi>ECH</bdi>.
- **تشخیص می‌دهد:** برای هر تلاش ناموفق، از روی همین نشانه‌ها و نحوهٔ پایان تلاش، علت را مشخص می‌کند؛ مثلاً بسته بودن <bdi>UDP</bdi>، دخالت در <bdi>TLS</bdi>، فیلتر بودن دروازه‌ها، مسدود بودن سرور ثبت‌نام یا خرابی هویت.
- **یاد می‌گیرد:** دو مسیر مسابقه یک تختهٔ مشترک دارند. دو شکست <bdi>UDP</bdi> (یا یکی، وقتی اثرانگشت شبکه از قبل ضعف <bdi>UDP</bdi>/<bdi>QUIC</bdi> را دیده) یعنی «<bdi>UDP</bdi> اینجا بسته است». یک قطع <bdi>TLS</bdi> یعنی «در <bdi>TLS</bdi> دخالت می‌شود».
- **تطبیق می‌دهد:** بقیهٔ مسابقه همان لحظه و بدون نیاز به هیچ تنظیمی دوباره چیده می‌شود:
  - پروتکل‌های <bdi>TCP</bdi> (یعنی <bdi>MASQUE</bdi> روی <bdi>HTTP/2</bdi>) جلوتر از <bdi>UDP</bdi> می‌آیند؛
  - تکه‌تکه‌سازی <bdi>TLS</bdi> و دست‌کم مبهم‌سازی سطح <bdi>Firewall</bdi> روشن می‌شود؛
  - اگر دروازه‌ها جواب ندهند، وایرگارد مبهم‌سازی <bdi>GFW</bdi> می‌گیرد؛
  - روش‌های <bdi>UDP</bdi> روی شبکه‌ای که <bdi>UDP</bdi> را می‌بندد بعد از ۷ ثانیه رها می‌شوند؛
  - روش‌های معادل دوبار تست نمی‌شوند.
  نردبان سریالی و پله‌های آخر هم همین‌طور تطبیق پیدا می‌کنند.
- **به خاطر می‌سپارد:** هم برای هر شبکه و هم برای هر اپراتور. کلید اپراتور یک هش <bdi>SHA-256</bdi> کوتاه‌شده است و نام اپراتور هرگز ذخیره نمی‌شود.
- **ترمیم می‌کند:** هویت خراب هسته را ترمیم می‌کند و همان روش را یک‌بار دیگر امتحان می‌کند. اگر خطای یکسانی هنگام اجرا روی دو پروتکل مختلف تکرار شود، نردبان زودتر متوقف می‌شود.
- اصلاحات این بازنگری:
  - تلاشی که خود مغز رها کرده، دیگر شاهد تازه حساب نمی‌شود؛ پس یک تشخیص خودش را تقویت نمی‌کند؛
  - خطی که از *بازیابی* هویت خبر می‌دهد، دیگر به‌عنوان هویت خراب خوانده نمی‌شود.

#### دروازهٔ هوشمند اتصال (<bdi>core/LiveDataProof.kt</bdi>): «راستی‌آزمایی اتصال» از همه‌جا حذف شد
- کلید **راستی‌آزمایی اتصال** هم از تنظیمات اتصال و هم از بخش کانفیگ‌های سفارشی حذف شد. فیلدهای ذخیره‌سازی آن و کلاس‌های <bdi>VerificationPolicy</bdi> و <bdi>CustomReadiness</bdi> هم پاک شدند.
- حالت اتصال <bdi>Verifying</bdi> به <bdi>Proving</bdi> تغییر کرد. متن وضعیت هم به‌جای «در حال بررسی سلامت اتصال…» حالا «دروازهٔ هوشمند: در انتظار عبور دادهٔ واقعی…» است.
- هر نشست (داخلی یا سفارشی، تنها یا زنجیره‌ای) فقط وقتی **«متصل»** گزارش می‌شود که یک درخواست <bdi>HTTP</bdi> واقعی از تونل رد شده و پاسخش برگشته باشد:
  - سه مقصد مستقل هم‌زمان امتحان می‌شوند؛
  - اولین پاسخ برنده است، معمولاً در کمتر از یک ثانیه؛
  - پاسخ خالی <bdi>SOCKS5</bdi> هرگز کافی نیست؛ پس اتصال فیک ممکن نیست؛
  - اگر هسته یا <bdi>Xray</bdi> بسته شود، دروازه همان لحظه تمام می‌شود.
- <bdi>DNS</bdi> دستگاه روی <bdi>UDP</bdi> فقط برای پنل عیب‌یابی سنجیده می‌شود و دیگر جلوی اتصال را نمی‌گیرد. همین شرط بود که <bdi>MASQUE</bdi> را از اتصال بازمی‌داشت.
- کانفیگ‌های سفارشی هم پیش از زنجیره شدن به همین روش اثبات می‌شوند (بودجهٔ ۴۰ ثانیه، بدون <bdi>geo-IP</bdi> و بدون شرط <bdi>UDP</bdi>).
- یک کارت توضیحی «دروازهٔ هوشمند اتصال»، همانند نسخهٔ ویندوز، در تنظیمات اتصال و در کانفیگ‌های سفارشی نمایش داده می‌شود.

- **مرحلهٔ اول زنجیره هم با عبور دادهٔ واقعی اثبات می‌شود:** پیش از شروع مرحلهٔ دوم (مثلاً <bdi>Psiphon</bdi> پشت <bdi>Aether</bdi>)، مرحلهٔ اول باید یک پاسخ <bdi>HTTP</bdi> واقعی را عبور دهد (دروازهٔ هوشمند)، نه فقط اتصال را بپذیرد. تشخیص گیرکردن <bdi>Tor</bdi> همچنان انتظار را زود تمام می‌کند.

- کلید **انتخاب خودکار کمترین پینگ** در کانفیگ‌های سفارشی حالا **به‌طور پیش‌فرض روشن** است (برای نصب تازه؛ انتخابی که قبلاً ذخیره کرده‌اید حفظ می‌شود).
- در زبان فارسی، خطاهای دستیار هوش مصنوعی فقط با جملهٔ فارسی نمایش داده می‌شوند و متن خام انگلیسی گوگل یا برنامه کنارشان نمی‌آید (در لاگ عیب‌یابی می‌ماند).

#### ممیزی امنیتی (۰ تا ۱۰۰) - همین انتشار

| # | حوزه | وزن | امتیاز | یافته‌ها |
|---|------|:---:|:-----:|----------|
| ۱ | رازها روی دیسک | ۱۵ | ۱۴ | هویت هسته بین نشست‌ها با <bdi>Android Keystore</bdi> مهر و موم می‌شود؛ گاوصندوق سریالی است و نسخهٔ خراب پاک می‌شود، نه مهر. <bdi>allowBackup=false</bdi>. |
| ۲ | رازها در خط فرمان | ۱۰ | ۱۰ | اطلاعات <bdi>Zero Trust</bdi> و نشانی پروکسی بالادستی فقط از راه محیط اجرای هسته فرستاده می‌شوند و در لاگ پوشانده می‌شوند. |
| ۳ | جلوگیری از نشت | ۱۵ | ۱۴ | <bdi>Kill switch</bdi> به‌طور پیش‌فرض روشن است، <bdi>IPv6</bdi> هم از تونل می‌رود، قواعد مستقیم هنگام اشتراک <bdi>LAN</bdi> معلق می‌شوند و خود برنامه از تونل خودش مستثناست. |
| ۴ | درستی دروازهٔ اتصال | ۱۰ | ۹ | بایت‌های پاسخ واقعی لازم است، مهلت‌ها محدودند و کاوش‌ها هیچ دادهٔ کاربری ندارند. مقصدهای <bdi>HTTP</bdi> عمومی‌اند (ریسک باقی‌ماندهٔ کم). |
| ۵ | حریم خصوصی مغز و حافظه | ۵ | ۵ | منطق خالص؛ فقط کلیدهای درهم‌شده و شناسهٔ روش، با تاریخ انقضا و سقف ۳۲ مورد ذخیره می‌شوند. |
| ۶ | اعتبارسنجی <bdi>TLS</bdi> | ۱۰ | ۸ | بررسی نام میزبان برای <bdi>Gemini</bdi> و موقعیت‌یابی؛ <bdi>usesCleartextTraffic=false</bdi>. پین گواهی <bdi>MASQUE</bdi> اختیاری است. |
| ۷ | وابستگی‌ها و زنجیرهٔ تأمین | ۱۵ | ۱۳ | دروازهٔ سخت‌گیرانهٔ <bdi>cargo audit</bdi> پیش و پس از بیلد، کف امنیتی وابستگی‌ها و یک استثنای محدود و ثبت‌شده. |
| ۸ | درستی بیلد و امضا | ۱۰ | ۶ | مانیفست درخت انتشار و پین اثرانگشت امضاکننده وجود دارد. اگر رازهای امضای خصوصی تنظیم نشده باشند، بیلدها با کلید تست **عمومی** <bdi>CI</bdi> امضا می‌شوند. این کلید عمداً تغییر نکرد تا نسخهٔ جدید روی نسخهٔ قبلی نصب شود. |
| ۹ | لاگ و عیب‌یابی | ۵ | ۵ | خروجی هسته در بیلد انتشار وارد <bdi>Logcat</bdi> نمی‌شود و گزارش عیب‌یابی پاک‌سازی‌شده است. |
| ۱۰ | اعتبارسنجی ورودی | ۵ | ۵ | پروفایل، <bdi>DNS</bdi>، قواعد، پروکسی بالادستی و گزینه‌های <bdi>TLS</bdi> پیش از رسیدن به هسته پاک‌سازی می‌شوند. |
| | **جمع** | **۱۰۰** | **۸۹** | **خوب.** ریسک اصلی باقی‌مانده کلید امضای عمومی <bdi>CI</bdi> است (اصالت، نه قابلیت به‌روزرسانی). برای انتشار عمومی، رازهای امضای خودتان را تنظیم کنید. |

### تازه‌ها نسبت به نسخهٔ 1.6.0

**ریشهٔ «کانکشن فیل» شدن‌ها - رفع شد**
- **هسته وسط کارش بسته می‌شد.** موتور پورت <span dir="ltr">SOCKS5</span> محلی را هنگام اجرا رزرو می‌کند اما فقط پس از تأیید تونل به آن پاسخ می‌دهد. برنامه باز بودن پورت (حدود ۰٫۳ ثانیه پس از اجرا) را آماده بودن موتور حساب می‌کرد و به هر تلاش فقط یک دست‌دهی ۴ ثانیه‌ای می‌داد؛ برای همین <span dir="ltr">MASQUE</span> «گاهی» وصل می‌شد و هر اسکن دروازه همیشه شکست می‌خورد. در هر دو لاگ تهران، همهٔ تلاش‌ها دقیقاً ۴٫۰ ثانیه پس از باز شدن پورت از بین رفته‌اند.
- **دروازهٔ آمادگی جدید** (<span dir="ltr">EngineReadiness</span>): در کل زمان هر تلاش صبر می‌کند تا موتور اعلام کند <span dir="ltr">SOCKS5</span> را سرویس می‌دهد و یک دست‌دهی واقعی موفق شود؛ با بسته شدن موتور همان لحظه تمام می‌شود. برای پروتکل دستی، حالت هوشمند، مرحلهٔ اول زنجیره‌ها و راه‌اندازی دوباره توسط ناظر.

**حالت هوشمند از نو نوشته شد - مسابقهٔ منصفانه و بهترین مسیر، نه اولین مسیر**
- **شروع منصفانه:** مسیرهای <span dir="ltr">WARP</span> (وایرگارد) و <span dir="ltr">MASQUE</span> در یک لحظه و با بودجهٔ زمانی یکسان برای هر روش شروع می‌شوند (سبک ۲۰، تقویت‌شده ۲۵، دوهاپی ۴۰ ثانیه). در 1.6.0 وایرگارد اول شروع می‌شد و <span dir="ltr">MASQUE</span> هفت ثانیه بعد، آن هم پس از یک نوبت جداگانهٔ ۴۵ ثانیه‌ای برای «مسیر ذخیره‌شده».
- **از سبک به سنگین:** اول وایرگارد ساده و <span dir="ltr">MASQUE</span> روی <span dir="ltr">HTTP/3</span> و <span dir="ltr">HTTP/2</span>؛ مبهم‌سازی <span dir="ltr">Noize</span>، <span dir="ltr">HTTP/2</span> با تکه‌تکه‌سازی <span dir="ltr">TLS</span> و در آخر <span dir="ltr">Gool</span> و <span dir="ltr">MASQUE×2</span> فقط در صورت نیاز.
- **بهترین مسیر برنده است:** مسابقه پس از اولین اثبات ۳٫۵ ثانیه ادامه می‌یابد تا مسیر دیگر هم فرصت داشته باشد؛ هر مسیر سالم از درون خودش سنجیده می‌شود (پینگ، دانلود حدود ۳۸۴ کیلوبایت، آپلود ۱۲۸ کیلوبایت، روی <span dir="ltr">TLS</span> تأییدشده) و با ضریب سربار هر پروتکل رتبه می‌گیرد. اگر بهترین مسیر به‌عنوان جلسه بالا نیاید، مسیر سالم بعدی امتحان می‌شود.
- **اولویت <span dir="ltr">HTTP/2</span>** روی اینترنت همراه، با سیم‌کارت ایرانی (<span dir="ltr">MCC 432</span>، حتی روی وای‌فای)، در شبکه‌های با محدودیت <span dir="ltr">UDP</span> و وقتی پراب جدید <span dir="ltr">QUIC</span> جوابی نگیرد.
- **حافظهٔ هر شبکه** فقط ترتیب را عوض می‌کند و شکست‌ها برای هر شبکه ثبت می‌شوند. **آخرین تلاش** (اسکن عمیق کل محدوده برای دو روش تمیزتر) فقط وقتی هیچ مسیری جواب نداد.

**رفع مشکلات دیگر**
- **توقف ناگهانی هسته با <span dir="ltr">ECH</span>:** مرحله‌های ضد <span dir="ltr">DPI</span> دیگر <span dir="ltr">ECH</span> را اجباری نمی‌کنند؛ بدون کلید <span dir="ltr">ECH</span> موتور شروع نمی‌کرد و هر تلاش تقویت‌شدهٔ <span dir="ltr">MASQUE</span> در ۰٫۴ ثانیه می‌مرد. <span dir="ltr">ECH</span> فقط انتخاب خود کاربر است.
- **تداخل هویت:** <span dir="ltr">Gool</span> هستهٔ 2.4.0 (وایرگارد داخل <span dir="ltr">MASQUE</span>) حالا در مسیر <span dir="ltr">MASQUE</span> اجرا می‌شود و دیگر هم‌زمان با <span dir="ltr">MASQUE</span> روی یک هویت و حافظهٔ دروازه نمی‌دود.
- **محدودهٔ اسکن:** اسکن <span dir="ltr">MASQUE</span> و <span dir="ltr">Gool</span> دیگر به محدوده‌های سنجیده‌شده برای وایرگارد (مثل <span dir="ltr">8.6.112.0/24</span>) محدود نمی‌شود.
- **نردبان:** از همان فهرست روش‌های مسابقه ساخته می‌شود و دو خانواده یکی‌درمیان می‌آیند؛ <span dir="ltr">MASQUE</span> روی <span dir="ltr">HTTP/2</span> در شبکه‌های «باز» دیگر جا نمی‌افتد.
- **<span dir="ltr">MASQUE</span> دستی:** مطابق تنظیمات ← <span dir="ltr">MASQUE</span> روی <span dir="ltr">HTTP/2</span> (بستر <span dir="ltr">TCP</span>) ← مرحلهٔ ضد <span dir="ltr">DPI</span>.
- **پنجرهٔ «تازه‌ها»:** فهرست 1.7.0؛ بخش «اگر به هیچ وجه متصل نشدید چه کنید؟» حفظ شد. خروجی پروفایل‌ها برچسب 1.7.0 دارد و خروجی‌های 1.6.0 همچنان وارد می‌شوند.
- **رفع «دکمهٔ اتصال واکنشی نشان نمی‌دهد» (در همین نسخه، گزارش‌شده روی <span dir="ltr">Galaxy S24 Ultra</span>):** وقتی اندروید مجوز <span dir="ltr">VPN</span> را بدون نمایش رد می‌کرد (برنامهٔ دیگری روی <span dir="ltr">Always-on VPN</span>، پروفایل کاری / <span dir="ltr">Secure Folder</span> / سیاست دستگاه، یا برنامه‌ای روی صفحه که جلوی «تأیید» را می‌گیرد)، نتیجه نادیده گرفته می‌شد و دکمه مرده به نظر می‌رسید. حالا رد شدن مجوز روی صفحهٔ اصلی و در عیب‌یابی نشان داده می‌شود، یک پنجره دلیل را توضیح می‌دهد و تنظیمات <span dir="ltr">VPN</span> را باز می‌کند، و مجوزی که بعد از ساخته‌شدن دوبارهٔ صفحه داده شود دیگر گم نمی‌شود. پنجرهٔ «تازه‌ها» این اصلاح را نشان می‌دهد و یک بار دیگر باز می‌شود.
- **بهبودهای همین نسخه:** اتصال اول سریع با انتخاب خودکار (وصل به اولین سرور سریع و سوییچ روی سریع‌ترین کل پس از پایان تست)؛ سوییچ فوری پس از «تست همه»؛ تست دوباره با تغییر <span dir="ltr">Wi-Fi</span>، اینترنت همراه یا سیم‌کارت؛ دکمه‌های سریع پینگ، تست سرعت و حذف روی هر کانفیگ؛ «تست سرعت همه» و مرتب‌سازی بر اساس پینگ، دانلود یا آپلود؛ کارت اتصال تعاملی (آمار تأخیر، نودهای زنجیره، ترافیک هر برنامه)؛ سازندهٔ یکپارچهٔ زنجیرهٔ اتصال در تنظیمات اتصال؛ «ارسال گزارش عیب‌یابی» به گیت‌هاب سازندگان (پاک‌سازی‌شده، با ثبت خود کاربر، قابل خاموش‌کردن). دسترسی ویژهٔ تازه: <span dir="ltr">PACKAGE_USAGE_STATS</span> (فقط پس از اجازهٔ کاربر و فقط برای فهرست ترافیک برنامه‌ها).

### نکات امنیتی (1.7.0)

هیچ مجوز، جزء یا شنوندهٔ تازه‌ای اضافه نشده است. فعالیت شبکهٔ جدید فقط هنگام اتصال هوشمند رخ می‌دهد: سنجش کیفیت **از درون همان تونلی که سنجیده می‌شود** انجام می‌شود (رفت‌وبرگشت به <span dir="ltr">1.1.1.1:80</span> و <span dir="ltr">HTTPS</span> به <span dir="ltr">speed.cloudflare.com</span> با بررسی گواهی و نام میزبان) و اثرانگشت شبکه یک بستهٔ ۱۲۰۰ بایتی <span dir="ltr">QUIC Version Negotiation</span> (بایت‌های تصادفی، بدون هویت و بدون نام سرور) مستقیم به دو دروازهٔ <span dir="ltr">MASQUE</span> کلادفلر می‌فرستد. <span dir="ltr">ECH</span> دیگر هرگز بدون اطلاع کاربر روشن یا خاموش نمی‌شود. آخرین ممیزی کامل همان ممیزی 1.6.0 است (<span dir="ltr">76 / 100</span>).

**بررسی وابستگی‌ها در CI:** هشدارهای امنیتی <span dir="ltr">RustSec</span> که پس از 1.6.0 منتشر شدند، پیش از کامپایل هسته با به‌روزرسانی قفل‌فایل برطرف می‌شوند: <span dir="ltr">quinn-proto 0.11.19</span>، <span dir="ltr">quinn-udp 0.5.16</span>، <span dir="ltr">url 2.5.9</span> و <span dir="ltr">cookie 0.18.3</span>. برای <span dir="ltr">RUSTSEC-2026-0459</span> (<span dir="ltr">SQLite</span> داخلی، <span dir="ltr">libsqlite3-sys 0.37.0</span>) نسخهٔ سازگاری وجود ندارد (به <span dir="ltr">rusqlite 0.41</span> نیاز دارد که <span dir="ltr">tor-dirmgr 0.46</span> نمی‌پذیرد)؛ این مورد یک استثنای محدود و مستند است، چون <span dir="ltr">SQLite</span> فقط حافظهٔ دایرکتوری <span dir="ltr">Tor</span> را در فضای خصوصی برنامه باز می‌کند، و استثنا فقط وقتی اعمال می‌شود که قفل‌فایل دقیقاً همین ساختار را نشان دهد. یک بررسی زودهنگام هم پیش از ساخت بخش‌های بومی اجرا می‌شود تا هشدار تازه در چند دقیقه خطا بدهد، نه بعد از یک ساعت.

جزئیات: <span dir="ltr">`docs/RELEASE_1.7.0.fa.md`</span>.

</div>
