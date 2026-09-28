## Aether 1.4.0

Installs straight over your current Aether — same signing key, no uninstall.
App version 1.4.0, version code 16, engine core 2.1.0.

A maintenance release, built entirely from what you reported about 1.3.0. No new
protocol, no new mode: five things that were broken, fixed where they broke.

### Aether engine upgraded from 2.0.0 to 2.1.0

The engine inside the app is now Aether core 2.1.0. Everything this app had tuned
in the engine (the congestion control and netstack fixes, manual endpoint ranges,
the quick-reconnect fallback) was carried over onto the new engine, not thrown away.

New from the engine: Psiphon built in, reconnects that remember the last eight
working gateways instead of one, a faster HTTP/2 scan, real `firewall` and `gfw`
noize profiles, a TLS 1.3 fingerprint for networks that block TLS 1.2, and Tor that
fetches its bridges through the tunnel. The scan mode once called Stealth is
**Verified** in the engine now; your saved setting keeps working.

### Psiphon now runs inside the engine

Until now the app carried its own copy of Psiphon next to the engine. The engine
now has Psiphon built in, so the app's copy is gone and there is only one Psiphon
left — no more two of them in one app.

- **Aether → Psiphon** and **Tor → Psiphon** use the engine's Psiphon.
- DNS, calls and QUIC keep working exactly as before.
- Exit countries and their flags are the same. If the country you picked has no
  Psiphon server right now, Aether still falls back to an automatic exit instead
  of getting stuck on "Connecting".

### New: bypass Iranian sites, block ads

Two new switches under **DNS & routing rules**. **Bypass Iranian sites** opens
Iranian sites directly, outside the tunnel. **Block ads and trackers** stops ads and
trackers before they load. Both lists are built in and refresh themselves weekly
through the tunnel. The Iranian bypass pauses while you share over your LAN.

### Routing rules work in Aether → Psiphon and Tor → Psiphon

In these two modes your block and direct rules did nothing. They now work there just
like in every other mode, and so do the two new switches.

### MASQUE-in-MASQUE no longer drops

With the outer hop on HTTP/2, a MASQUE-in-MASQUE session kept dropping. It now stays
connected.

### Gemini Live on home Wi-Fi no longer freezes the connection

In the Psiphon modes, running Gemini Live on home Wi-Fi pushed ping past 1000 ms and
stopped all data until Gemini was closed. Fixed: the connection keeps working while
Gemini Live runs.

### The app no longer takes ten seconds to open

If you told us *"the app opens slowly"*, *"it stays on a black screen and only
works again after I clear data"*, or *"it got worse the longer I used it"* — this
was one bug, and it is fixed.

Aether keeps an encrypted diagnostics log so that a crash is still readable after
a restart. On every cold start it was **decrypting that entire file on the main
thread before the first screen could be drawn** — and the file is stored as
thousands of separately sealed records, each one a trip into the phone's hardware
keystore. Then it threw away all but the last 800 lines. Clearing app data
"fixed" it because that deletes the file.

Now the log is restored in the background, and only the part that is actually
kept gets decrypted. Nothing about its protection changed: it is still sealed
with AES-256-GCM under a key that cannot leave your phone, and it is still never
written unencrypted.

This is also what was behind the crash some of you hit when connecting or
disconnecting (*"the app suddenly closed"*): Android gives an app ten seconds to
show its VPN notification, and a main thread stuck in the keystore cannot answer
in time. 1.3.0 added a net to survive that crash; this release removes the reason
for it.

### The widget

- **It no longer sticks on "Disconnecting…".** Two separate causes: the widget was
  never repainted on the final step of a disconnect, and after a reboot or a
  launcher restart it was reading a connection state that only exists inside the
  running app — so it showed whatever it had last been told. It now stores its own
  state, and a state that means "busy" is never trusted after a restart, because
  the work it described is gone with the process.
- **It is 1×1 now**, not 3×1 — and you can still drag it wider if you liked the old
  shape.
- **The power icon takes the colour of the connection**, so you can see at a glance
  whether you are connected without reading the label.
- **The notification's button says "Disconnect"**, not "Disconnecting…". It was
  labelled with a status by mistake, so the notification read "Disconnecting…" the
  whole time the tunnel was up.

### Zero Trust: sign in first, then connect

If you use **E-mail code** under Zero Trust, you can now sign in *before* you
connect. Open **Settings → Zero Trust**, enter your team and work e-mail, and tap
**Sign in**: your organization e-mails a one-time code, you type it in the same
window, and that's it. The next connect joins the organization without stopping
to ask for anything. The sign-in is kept encrypted on your phone, only for that
team and that address, and the screen shows how long it stays valid.

Skip the sign-in and nothing is lost: the code is still asked for in a dialog
while connecting, as before, and **Not now** in that dialog stops the connect at
once. That dialog is also the way to go if Cloudflare is only reachable through
your upstream proxy.

The same page now also tells you whether this device is **already a member** (then
no sign-in is needed at all), lets you **remove this device's membership** if your
organization revoked it or you want to join as someone else, and, for a
**service token**, has a **Test** button that checks the client ID and secret
exactly the way the engine will. If you use an **enrolment token**, the app still
tells you whether what you pasted is a token and how long it has left.

### A working server is no longer thrown away

The app was telling the engine to reuse a remembered server only if it answered
faster than about 300 ms — and on the networks people actually reported from,
nothing answers that fast. So the remembered server was discarded every time and
every reconnect paid for a full search. In one report the discarded server was
working, the search that replaced it found nothing in five minutes, and the connect
failed outright.

That check is now **off by default**, so a remembered server that still answers is
simply used, as the engine has always done on its own. If you are on a fast
connection and want the app to keep hunting for the best server, the switch is still
there under Transport & anti-DPI — with limits that match real networks now.

### Tor → Aether connects now

That mode never worked, in 1.3.0 either. Tor itself was fine every time — it was
the app, asking the engine to search 896 addresses through a Tor circuit in 45
seconds, which cannot work. It now waits for Tor to be ready first, then runs the
engine's shortest search, starting with Cloudflare's known gateways and stopping at
the first one that answers through Tor, with a time budget sized for Tor.

### The About card is in Persian now

The two feature lists in **About** were baked into the code in English and never
followed the app's language. They are translated now, in plain language rather than
word-for-word, and right-aligned like everything else.

### Persian menus read correctly again

Some rows in the settings were left-aligned instead of right-aligned, and lines
mixing Persian with an English word came out jumbled. Both are fixed throughout the
app: an English word or address inside a Persian sentence now stays left-to-right
as a block, and the Persian around it stays right-to-left.

### The home screen uses the whole screen again

On taller phones the title, the on/off button and the connection card were pinned
to the top with empty space underneath. They are centred now. On shorter phones
nothing changes — there the layout already filled the height.

### Now runs on Android TV

Pick the APK for your TV's architecture (`arm64-v8a` on almost everything) and it
installs and appears in the launcher. It was never blocked by anything in the
tunnel — the app simply did not declare that it works without a touchscreen, so TVs
filtered it out.

The screen layout is the phone one and you drive it with the remote's pointer. It has
not been redesigned for D-pad navigation.

### You can type an MTU now

Under Transport & anti-DPI. The presets are still there; the field next to them takes any value
between 1280 and 9000. Lower it if the tunnel connects but some sites or Telegram
stall — that is almost always MTU.

### Aether tells you when Android is throttling it

*"When I turn the screen off and on the connection drops — it still says connected
but nothing loads."* That is Android suspending the app, and only you can allow it to
keep running. A row appears in **Settings** while that permission is missing and
takes you straight to the right system screen. Once it is granted, the row goes away.

### If the AI stopped working, your Gemini key expired

Google retired the old style of Gemini API keys during 2026 — all of them stopped
being accepted in September 2026. If yours used to work and now does not, create a
new key in Google AI Studio and paste that in. The app now says this instead of just
"key rejected".

### Your own ECH configuration

If you have an ECHConfigList, you can paste it under Transport & anti-DPI instead of letting the
app fetch one — useful where fetching the default one is exactly what your network
blocks. Pointing ECH at a different domain, the way v2rayNG does, is not possible:
the engine does not accept a domain there.

### Sharing over your LAN: username/password are yours, WebRTC is locked

The shared proxy's **username and password can now be set**: authentication is
optional (off by default), and when you turn it on you can keep the generated
password, type your own, or generate a new one - RFC 1929 for SOCKS5, Basic for
HTTP. It is still stored sealed under a key that cannot leave the phone.

The **ports stay fixed at 10810 (SOCKS5) and 10811 (HTTP)** on purpose: they are
what you type into a PC or a TV, and moving them would silently break every
device you already set up.

Also fixed in sharing: **Mobile hotspot** now listens on every tethering link at
once (Wi-Fi hotspot, USB, Bluetooth) and shows each address; **`http://aether.check`**
opens on every path (HTTP, SOCKS5, VPN-mode clients); and the setup page
(`http://<phone>:10811/`) runs a **live WebRTC leak test** and gives a one-click
WebRTC lock for Chrome, Edge, Brave and Firefox.

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

### Private Space works now

Running Aether in Android's Private Space (or a work profile) while it was already
connected in your Main Space used to fail for the second one, on every protocol,
with nothing in the log but a local port that was already busy. The two spaces are
separate users but share one network, and Aether insisted on the same five local
port numbers in both.

It now takes the usual ports when they are free — so nothing changes for you if you
only run one copy — and steps to the next free ones when they are not.

**Not covered: sharing over your LAN.** The second copy still cannot share, because
those port numbers are ones you type into a PC or a TV and moving them behind your
back would be worse than failing. Making them adjustable is a separate request
(#28) and is not in this release. The two copyable rows under **Settings → Apps**
do now show the port actually in use rather than the standard one.

### Dual-SIM phones detect the right operator

With two SIMs, Aether read the operator from SIM 1 even when mobile data was going
through SIM 2 — so Automatic built its plan for the wrong network. It now asks for
the SIM that is actually carrying data. No new permission.

### x86_64 builds

For Chromebooks and emulators. Pick the `x86_64` APK there; `arm64-v8a` is still
the one for phones.

### Build fix

The release build could fail with "this binary has no Tor support" on a binary
that had it — a shell pipeline reporting the wrong exit status. Nothing about the
app, everything about whether a release can be cut at all.

### 🔒 Security audit 1.4.0: **92 / 100**

A fresh 0-100 audit of the whole app, with the LAN share scored as its own area
for the first time. Full report:
[`docs/SECURITY_AUDIT_1.4.0.md`](../docs/SECURITY_AUDIT_1.4.0.md).
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

### Verify what you install

Every release publishes a per-ABI APK with its SHA-256 sum, and the signer
fingerprint is printed in the build log — compare the list at the bottom of this
page with what you see in **About**. Full explanation in
[`docs/SIGNING.md`](../docs/SIGNING.md).

---

<div dir="rtl" align="right">

<h1 dir="rtl" align="right">‏<span dir="ltr">Aether</span> نسخهٔ ۱.۴.۰</h1>

<p dir="rtl" align="right">‏مستقیم روی نسخهٔ فعلی اتر نصب می‌شود — همان کلید امضا، بدون حذف برنامه. نسخهٔ برنامه ۱.۴.۰، کد نسخه ۱۶، هستهٔ موتور ۲.۱.۰.</p>

<p dir="rtl" align="right">‏یک نسخهٔ نگهداری، تمامش از گزارش‌های خودتان روی ۱.۳.۰ درآمده. نه پروتکل تازه‌ای، نه حالت تازه‌ای: پنج چیز که خراب بود، از همان جایی که خراب بود درست شد.</p>

<h2 dir="rtl" align="right">‏ارتقای موتور اتر از ۲.۰.۰ به ۲.۱.۰</h2>

<p dir="rtl" align="right">‏موتور داخل برنامه حالا هستهٔ اتر ۲.۱.۰ است. هر چیزی که برنامه در موتور تنظیم کرده بود (اصلاحات کنترل ازدحام و نت‌استک، رنج دستی اندپوینت، بازگشت اتصال سریع) روی موتور تازه منتقل شد، نه اینکه دور ریخته شود.</p>

<p dir="rtl" align="right">‏تازه‌های موتور: سایفون داخلی، اتصال مجددی که هشت گیت‌وی سالم آخر را به‌خاطر می‌سپارد نه یکی، اسکن سریع‌تر <span dir="ltr">HTTP/2</span>، پروفایل‌های واقعی نویز <span dir="ltr">firewall</span> و <span dir="ltr">gfw</span>، اثرانگشت <span dir="ltr">TLS 1.3</span> برای شبکه‌هایی که <span dir="ltr">TLS 1.2</span> را می‌بندند، و تورِ که بریج‌هایش را از داخل تونل می‌گیرد. حالت اسکنی که «مخفی (<span dir="ltr">Stealth</span>)» بود در موتور حالا <span dir="ltr">Verified</span> است؛ تنظیم ذخیره‌شدهٔ شما همچنان کار می‌کند.</p>

<h2 dir="rtl" align="right">‏سایفون حالا داخل موتور اجرا می‌شود</h2>

<p dir="rtl" align="right">‏تا امروز برنامه یک نسخهٔ جداگانه از سایفون را کنار موتور داشت. موتور حالا خودش سایفون دارد، پس نسخهٔ برنامه حذف شد و فقط یک سایفون باقی مانده — دیگر دو سایفون در یک برنامه نیست.</p>

<ul dir="rtl" align="right">
<li align="right">حالت‌های <strong><span dir="ltr">Aether → Psiphon</span></strong> و <strong><span dir="ltr">Tor → Psiphon</span></strong> از سایفونِ موتور استفاده می‌کنند.</li>
<li align="right"><span dir="ltr">DNS</span>، تماس‌ها و <span dir="ltr">QUIC</span> دقیقاً مثل قبل کار می‌کنند.</li>
<li align="right">کشورهای خروجی و پرچم‌هایشان همان است. اگر کشوری که انتخاب کرده‌اید الان سرور سایفون نداشته باشد، اتر به‌جای گیر کردن روی «در حال اتصال» به خروجی خودکار برمی‌گردد.</li>
</ul>

<h2 dir="rtl" align="right">‏تازه: بای‌پس سایت‌های ایرانی و حذف تبلیغات</h2>

<p dir="rtl" align="right">‏دو کلید تازه در بخش <strong><span dir="ltr">DNS</span> و قواعد مسیریابی</strong>. <strong>بای‌پس سایت‌های ایرانی</strong> سایت‌های ایرانی را مستقیم و بیرون از تونل باز می‌کند. <strong>حذف تبلیغات و ردیاب‌ها</strong> جلوی تبلیغات و ردیاب‌ها را پیش از بارگذاری می‌گیرد. هر دو فهرست داخل برنامه هستند و هر هفته از داخل تونل به‌روز می‌شوند. بای‌پس ایران هنگام اشتراک‌گذاری روی شبکهٔ محلی متوقف می‌شود.</p>

<h2 dir="rtl" align="right">‏قواعد مسیریابی در <span dir="ltr">Aether → Psiphon</span> و <span dir="ltr">Tor → Psiphon</span> کار می‌کنند</h2>

<p dir="rtl" align="right">‏در این دو حالت قواعد مسدودسازی و مستقیمِ شما هیچ اثری نداشتند. حالا مثل بقیهٔ حالت‌ها کار می‌کنند، دو کلید تازه هم همین‌طور.</p>

<h2 dir="rtl" align="right">‏<span dir="ltr">MASQUE-in-MASQUE</span> دیگر قطع نمی‌شود</h2>

<p dir="rtl" align="right">‏وقتی هاپ بیرونی روی <span dir="ltr">HTTP/2</span> بود، اتصال <span dir="ltr">MASQUE-in-MASQUE</span> مدام قطع می‌شد. حالا وصل می‌ماند.</p>

<h2 dir="rtl" align="right">‏<span dir="ltr">Gemini Live</span> روی وای‌فای خانه دیگر اتصال را قفل نمی‌کند</h2>

<p dir="rtl" align="right">‏در حالت‌های سایفون، اجرای <span dir="ltr">Gemini Live</span> روی وای‌فای خانه پینگ را از ۱۰۰۰ میلی‌ثانیه بالاتر می‌برد و تا بستن جمینای هیچ داده‌ای رد نمی‌شد. درست شد: اتصال هم‌زمان با <span dir="ltr">Gemini Live</span> کار می‌کند.</p>

<h2 dir="rtl" align="right">‏باز شدن برنامه دیگر ۱۰ ثانیه طول نمی‌کشد</h2>

<p dir="rtl" align="right">‏اگر گفته بودید «اپ کند باز میشه»، «صفحه سیاه میمونه و تا <span dir="ltr">clear data</span> نکنم باز نمیشه» یا «هرچی بیشتر استفاده می‌کنم بدتر میشه» — همهٔ این‌ها یک باگ بود و درست شد.</p>

<p dir="rtl" align="right">‏اتر یک لاگ تشخیصی رمزشده نگه می‌دارد تا بعد از یک کرش هم قابل خواندن باشد. در هر بار باز شدن سرد، <strong>کل آن فایل را روی ترد اصلی رمزگشایی می‌کرد، پیش از آنکه اولین صفحه کشیده شود</strong> — و آن فایل هزاران رکورد جداگانهٔ مهرشده است که هر کدام یک رفت‌وبرگشت به کی‌استور سخت‌افزاری گوشی لازم دارد. بعد هم همه را جز ۸۰۰ خط آخر دور می‌ریخت. <span dir="ltr">clear data</span> «درستش می‌کرد» چون همان فایل را حذف می‌کند.</p>

<p dir="rtl" align="right">‏حالا لاگ در پس‌زمینه بازیابی می‌شود و فقط همان بخشی که واقعاً نگه داشته می‌شود رمزگشایی می‌گردد. هیچ چیز از محافظتش عوض نشده: همچنان با <span dir="ltr">AES-256-GCM</span> زیر کلیدی که از گوشی شما بیرون نمی‌رود مهر می‌شود و همچنان هرگز بی‌رمز نوشته نمی‌شود.</p>

<p dir="rtl" align="right">‏همین چیز پشت آن کرشی هم بود که بعضی‌هایتان موقع وصل یا قطع کردن می‌دیدید («برنامه یهو بسته میشه»): اندروید به یک اپ ده ثانیه فرصت می‌دهد نوتیفیکیشن <span dir="ltr">VPN</span> خود را نشان دهد، و تردی که در کی‌استور گیر کرده نمی‌تواند به‌موقع جواب دهد. نسخهٔ ۱.۳.۰ توری گذاشت که از آن کرش جان سالم ببرد؛ این نسخه دلیلش را برمی‌دارد.</p>

<h2 dir="rtl" align="right">‏ویجت</h2>

<ul dir="rtl" align="right">
<li align="right"><strong>دیگر روی «در حال قطع…» گیر نمی‌کند.</strong> دو علت جدا داشت: ویجت در آخرین مرحلهٔ قطع شدن هیچ‌وقت دوباره رسم نمی‌شد، و بعد از ریبوت یا ری‌استارت لانچر وضعیتی را می‌خواند که فقط درون اپِ در حال اجرا وجود دارد — پس همان آخرین چیزی را نشان می‌داد که به آن گفته شده بود. حالا وضعیت خودش را ذخیره می‌کند، و به وضعیتی که معنایش «مشغول» است بعد از ری‌استارت اعتماد نمی‌شود، چون کاری که توصیفش می‌کرد با همان پروسه رفته است.</li>
<li align="right"><strong>حالا ۱×۱ است</strong>، نه ۳×۱ — و اگر شکل قبلی را می‌پسندیدید، هنوز می‌توانید پهنش کنید.</li>
<li align="right"><strong>آیکون روشن/خاموش رنگ وضعیت اتصال را می‌گیرد</strong>، پس بدون خواندن نوشته هم می‌فهمید وصل هستید یا نه.</li>
<li align="right"><strong>دکمهٔ نوتیفیکیشن «قطع اتصال» می‌نویسد</strong>، نه «در حال قطع…». اشتباهاً با یک وضعیت برچسب خورده بود، پس نوتیفیکیشن تمام مدتی که تونل بالا بود «در حال قطع…» نشان می‌داد.</li>
</ul>

<h2 dir="rtl" align="right">‏<span dir="ltr">Zero Trust</span>: اول وارد شو، بعد وصل شو</h2>

<p dir="rtl" align="right">‏اگر در بخش <span dir="ltr">Zero Trust</span> از <strong>کد ایمیلی</strong> استفاده می‌کنید، حالا می‌توانید <em>پیش از</em> وصل شدن وارد شوید. به <strong>تنظیمات ← <span dir="ltr">Zero Trust</span></strong> بروید، نام تیم و ایمیل کاری را وارد کنید و <strong>ورود</strong> را بزنید: سازمان یک کد یک‌بارمصرف ایمیل می‌کند، آن را در همان پنجره وارد می‌کنید و تمام. اتصال بعدی بدون اینکه چیزی بپرسد عضو سازمان می‌شود. ورود به‌صورت رمزشده روی گوشی و فقط برای همان تیم و همان نشانی نگه داشته می‌شود و صفحه نشان می‌دهد تا کی معتبر است.</p>

<p dir="rtl" align="right">‏اگر وارد نشوید چیزی از دست نمی‌رود: کد مثل قبل در حین اتصال در یک پنجره پرسیده می‌شود و دکمهٔ <strong>حالا نه</strong> در آن پنجره اتصال را همان لحظه متوقف می‌کند. اگر کلادفلر فقط از راه پروکسی بالادستی در دسترس است، راهش همین پنجره است.</p>

<p dir="rtl" align="right">‏همین صفحه حالا می‌گوید این دستگاه <strong>از قبل عضو است</strong> یا نه (در آن صورت اصلاً ورود لازم نیست)، اجازه می‌دهد اگر سازمان دستگاه را حذف کرده یا می‌خواهید با کاربر دیگری عضو شوید <strong>عضویت این دستگاه را حذف کنید</strong>، و برای <strong>توکن سرویس</strong> یک دکمهٔ <strong>آزمایش</strong> دارد که شناسه و رمز را دقیقاً مثل موتور بررسی می‌کند. اگر از <strong>توکن عضویت</strong> استفاده می‌کنید، برنامه همچنان می‌گوید آنچه چسبانده‌اید توکن است یا نه و چقدر اعتبار دارد.</p>

<h2 dir="rtl" align="right">‏سروری که کار می‌کند دیگر دور ریخته نمی‌شود</h2>

<p dir="rtl" align="right">‏برنامه به موتور می‌گفت سرورِ به‌خاطر‌سپرده را فقط در صورتی دوباره استفاده کن که سریع‌تر از حدود ۳۰۰ میلی‌ثانیه جواب بدهد — و در شبکه‌هایی که واقعاً از آن‌ها گزارش رسیده، هیچ چیزی این‌قدر سریع جواب نمی‌دهد. پس سرورِ به‌خاطر‌سپرده هر بار دور ریخته می‌شد و هر اتصال مجدد هزینهٔ یک جست‌وجوی کامل را می‌پرداخت. در یکی از گزارش‌ها سروری که دور ریخته شد کار می‌کرد، جست‌وجویی که جایش را گرفت در پنج دقیقه هیچ نیافت، و اتصال کلاً شکست خورد.</p>

<p dir="rtl" align="right">‏آن بررسی حالا <strong>به‌طور پیش‌فرض خاموش</strong> است، پس سرورِ به‌خاطر‌سپرده‌ای که جواب می‌دهد همان‌طور که موتور همیشه خودش عمل می‌کرد، استفاده می‌شود. اگر روی اتصال سریعی هستید و می‌خواهید برنامه دنبال بهترین سرور بگردد، سوئیچش هنوز زیر «انتقال و ضد-<span dir="ltr">DPI</span>» هست — با حدودی که این بار با شبکه‌های واقعی می‌خواند.</p>

<h2 dir="rtl" align="right">‏<span dir="ltr">Tor → Aether</span> حالا وصل می‌شود</h2>

<p dir="rtl" align="right">‏این حالت هیچ‌وقت کار نمی‌کرد، در ۱.۳.۰ هم نه. خودِ تور هر بار سالم بود — مشکل خودِ برنامه بود که از موتور می‌خواست ۸۹۶ نشانی را از داخل یک مدار تور در ۴۵ ثانیه جست‌وجو کند، که ممکن نیست. حالا اول صبر می‌کند تور آماده شود، بعد کوتاه‌ترین جست‌وجوی موتور را اجرا می‌کند: از گیت‌وی‌های شناخته‌شدهٔ کلادفلر شروع می‌کند و با اولین گیت‌وی‌ای که از داخل تور جواب بدهد متوقف می‌شود، با زمانی که برای تور کافی است.</p>

<h2 dir="rtl" align="right">‏کارت «درباره» حالا فارسی است</h2>

<p dir="rtl" align="right">‏دو فهرست قابلیت در بخش <strong>درباره</strong> به‌صورت انگلیسی داخل خودِ کد نوشته شده بودند و هیچ‌وقت زبان برنامه را دنبال نمی‌کردند. حالا ترجمه شده‌اند — روان و به زبان ساده، نه کلمه‌به‌کلمه — و مثل بقیهٔ برنامه راست‌چین هستند.</p>

<h2 dir="rtl" align="right">‏منوهای فارسی دوباره درست خوانده می‌شوند</h2>

<p dir="rtl" align="right">‏بعضی ردیف‌های تنظیمات چپ‌چین بودند به‌جای راست‌چین، و خط‌هایی که فارسی و یک کلمهٔ انگلیسی را با هم داشتند بهم‌ریخته نشان داده می‌شدند. هر دو در کل برنامه درست شد: یک کلمه یا نشانی انگلیسی داخل جملهٔ فارسی حالا به‌عنوان یک بلوک چپ‌به‌راست می‌ماند و فارسیِ دور آن راست‌به‌چپ.</p>

<h2 dir="rtl" align="right">‏صفحهٔ خانه دوباره از تمام صفحه استفاده می‌کند</h2>

<p dir="rtl" align="right">‏روی گوشی‌های بلندتر، عنوان و دکمهٔ روشن/خاموش و کارت اتصال به بالای صفحه چسبیده بودند و زیرشان فضای خالی می‌ماند. حالا وسط‌چین شده‌اند. روی گوشی‌های کوتاه‌تر چیزی عوض نمی‌شود — آنجا چیدمان از قبل تمام ارتفاع را پر می‌کرد.</p>

<h2 dir="rtl" align="right">‏حالا روی <span dir="ltr">Android TV</span> اجرا می‌شود</h2>

<p dir="rtl" align="right">‏فایل مناسب معماری تلویزیونتان را بردارید (روی تقریباً همه <span dir="ltr">arm64-v8a</span>)؛ نصب می‌شود و در لانچر ظاهر می‌شود. هیچ‌وقت چیزی در تونل مانعش نبود — برنامه فقط اعلام نکرده بود که بدون صفحهٔ لمسی کار می‌کند، پس تلویزیون‌ها فیلترش می‌کردند.</p>

<p dir="rtl" align="right">‏چیدمان صفحه همان چیدمان گوشی است و با نشانگر ریموت کار می‌کنید. برای پیمایش با <span dir="ltr">D-pad</span> بازطراحی نشده.</p>

<h2 dir="rtl" align="right">‏حالا می‌توانید <span dir="ltr">MTU</span> را تایپ کنید</h2>

<p dir="rtl" align="right">‏زیر «انتقال و ضد-<span dir="ltr">DPI</span>». گزینه‌های آماده هنوز هستند؛ فیلد کنارشان هر مقداری بین ۱۲۸۰ و ۹۰۰۰ را می‌گیرد. اگر تونل وصل می‌شود ولی بعضی سایت‌ها یا تلگرام باز نمی‌شوند کمترش کنید — تقریباً همیشه <span dir="ltr">MTU</span> است.</p>

<h2 dir="rtl" align="right">‏اتر می‌گوید کِی اندروید دارد محدودش می‌کند</h2>

<p dir="rtl" align="right">‏«وقتی صفحه را خاموش و روشن می‌کنم اتصال می‌افتد — می‌نویسد وصل است ولی چیزی باز نمی‌شود.» این اندروید است که برنامه را متوقف می‌کند، و فقط شما می‌توانید اجازهٔ ادامهٔ اجرا را بدهید. تا وقتی این اجازه نباشد، ردیفی در <strong>تنظیمات</strong> ظاهر می‌شود و شما را مستقیم به صفحهٔ درست سیستم می‌برد. بعد از دادن اجازه، آن ردیف می‌رود.</p>

<h2 dir="rtl" align="right">‏اگر هوش مصنوعی از کار افتاده، کلید جمینای شما منقضی شده</h2>

<p dir="rtl" align="right">‏گوگل در سال ۲۰۲۶ سبک قدیمی کلیدهای <span dir="ltr">API</span> جمینای را بازنشسته کرد — از سپتامبر ۲۰۲۶ هیچ‌کدامشان پذیرفته نمی‌شوند. اگر کلید شما قبلاً کار می‌کرد و حالا نه، در <span dir="ltr">Google AI Studio</span> یک کلید تازه بسازید و همان را بچسبانید. برنامه حالا همین را می‌گوید، نه فقط «کلید پذیرفته نشد».</p>

<h2 dir="rtl" align="right">‏تنظیم <span dir="ltr">ECH</span> خودتان</h2>

<p dir="rtl" align="right">‏اگر یک <span dir="ltr">ECHConfigList</span> دارید، می‌توانید زیر «انتقال و ضد-<span dir="ltr">DPI</span>» بچسبانیدش به‌جای اینکه برنامه خودش یکی بگیرد — جایی به کار می‌آید که شبکه‌تان دقیقاً همان گرفتنِ پیش‌فرض را بلاک می‌کند. نشانه‌گرفتن <span dir="ltr">ECH</span> به یک دامنهٔ دیگر، آن‌طور که <span dir="ltr">v2rayNG</span> می‌کند، ممکن نیست: موتور در آن جا دامنه نمی‌پذیرد.</p>

<h2 dir="rtl" align="right">‏اشتراک روی شبکهٔ محلی: نام‌کاربری و رمز دست شماست، WebRTC قفل می‌شود</h2>

<p dir="rtl" align="right">‏<strong>نام‌کاربری و رمز پراکسی مشترک حالا قابل تنظیم است</strong>: احراز هویت اختیاری است (پیش‌فرض خاموش) و وقتی روشنش کنید می‌توانید رمز ساخته‌شده را نگه دارید، رمز خودتان را بنویسید یا رمز تازه بسازید. همچنان زیر کلیدی که از گوشی بیرون نمی‌رود مهر می‌شود.</p>

<p dir="rtl" align="right">‏<strong>پورت‌ها عمداً ثابت می‌مانند: <span dir="ltr">10810</span> برای <span dir="ltr">SOCKS5</span> و <span dir="ltr">10811</span> برای <span dir="ltr">HTTP</span></strong>؛ این‌ها چیزی است که در کامپیوتر یا تلویزیون وارد می‌کنید و جابه‌جا کردنشان همهٔ دستگاه‌های تنظیم‌شده را بی‌صدا از کار می‌اندازد.</p>

<p dir="rtl" align="right">‏همچنین: حالت <strong>هات‌اسپات موبایل</strong> حالا روی همهٔ اتصال‌ها (هات‌اسپات وای‌فای، <span dir="ltr">USB</span>، بلوتوث) گوش می‌دهد و آدرس هر کدام را نشان می‌دهد؛ <span dir="ltr">http://aether.check</span> از هر مسیری باز می‌شود؛ و صفحهٔ تنظیم (<span dir="ltr">http://&lt;phone&gt;:10811/</span>) نشت WebRTC را زنده آزمایش می‌کند و قفل یک‌کلیکی WebRTC برای کروم، اج، بریو و فایرفاکس می‌دهد.</p>

<h2 dir="rtl" align="right">‏صفحه‌های اشتراک <span dir="ltr">VPN</span> از نو طراحی شدن (فارسی و انگلیسی)</h2>

<p dir="rtl" align="right">‏صفحه‌هایی که دستگاه مهمان می‌بینه، یعنی <span dir="ltr">http://aether.check/</span> و صفحهٔ راه‌اندازی <span dir="ltr">http://&lt;phone&gt;:10811/</span>، یه مشت متن قاطی انگلیسی و فارسی بودن که رو گوشی کلاً بهم می‌ریخت. از نو ساخته شدن:</p>

<ul dir="rtl" align="right">
<li align="right"><strong>یه طراحی تیره و سورمه‌ای</strong> که رو گوشی، لپ‌تاپ و مرورگر تلویزیون خوب خونده می‌شه.</li>
<li align="right"><strong>دکمهٔ انتخاب زبان بالای صفحه:</strong> فارسی (پیش‌فرض) یا <span dir="ltr">English</span>. انتخابت تو همون مرورگر یادش می‌مونه.</li>
<li align="right"><strong>همه‌چی راست‌چینه</strong>، با فونت وزیر (<span dir="ltr">Vazirmatn / Vazir</span>) اگه رو دستگاه باشه و با فونت فارسی خود سیستم اگه نباشه. هر کلمهٔ انگلیسی، آدرس، پورت یا اسم فایل وسط جملهٔ فارسی، چپ‌چین و یه‌تیکه می‌مونه که جمله بهم نریزه.</li>
<li align="right"><strong>آموزش قدم‌به‌قدم با فارسی خودمونی:</strong> دکمهٔ کپی برای آدرس‌های <span dir="ltr">PAC</span> و <span dir="ltr">HTTP</span> و <span dir="ltr">SOCKS5</span>، راهنمای تنظیم برای ویندوز، مک، اندروید، آیفون، فایرفاکس و تلویزیون، و راهنمای <strong>هر دو حالت اشتراک، وای‌فای خونه و هات‌اسپات گوشی</strong>؛ حالتی که گوشی الان توشه باز و علامت‌خورده نشون داده می‌شه.</li>
<li align="right">تست زندهٔ <span dir="ltr">WebRTC</span> و قفل‌های یک‌کلیکی مثل قبل کار می‌کنن، فقط حالا به هر دو زبان توضیح داده شدن.</li>
<li align="right">این صفحه‌ها هیچی از اینترنت بار نمی‌کنن و هیچ‌وقت رمز اشتراک رو نشون نمی‌دن.</li>
</ul>

<h2 dir="rtl" align="right">‏حالا <span dir="ltr">Private Space</span> کار می‌کند</h2>

<p dir="rtl" align="right">‏پیش‌تر اگر اتر در فضای اصلی وصل بود، اجرای آن در <span dir="ltr">Private Space</span> (یا پروفایل کاری) برای نمونهٔ دوم شکست می‌خورد — با هر پروتکلی، و در لاگ چیزی جز یک پورت محلی اشغال دیده نمی‌شد. این دو فضا کاربر جدا دارند ولی یک شبکه را مشترکاً استفاده می‌کنند، و اتر در هر دو روی همان پنج شمارهٔ پورت محلی پافشاری می‌کرد.</p>

<p dir="rtl" align="right">‏حالا اگر پورت‌های همیشگی آزاد باشند همان‌ها را می‌گیرد — پس اگر فقط یک نسخه اجرا می‌کنید هیچ چیز برایتان عوض نمی‌شود — و اگر آزاد نباشند به اولین پورت آزاد بعدی می‌رود.</p>

<p dir="rtl" align="right">‏<strong>پوشش داده نشده: اشتراک روی شبکهٔ محلی.</strong> نسخهٔ دوم هنوز نمی‌تواند اشتراک بگذارد، چون آن شماره‌های پورت چیزی است که شما در کامپیوتر یا تلویزیون وارد می‌کنید و جابه‌جا کردنشان بی‌خبر از شما بدتر از شکست خوردن است. قابل تنظیم کردنشان درخواست جداگانه‌ای است (<span dir="ltr">#28</span>) و در این نسخه نیست. آن دو ردیف قابل‌کپی در <strong>تنظیمات ← برنامه‌ها</strong> حالا پورتی را نشان می‌دهند که واقعاً در استفاده است، نه پورت استاندارد.</p>

<h2 dir="rtl" align="right">‏گوشی‌های دوسیم‌کارته اپراتور درست را تشخیص می‌دهند</h2>

<p dir="rtl" align="right">‏با دو سیم‌کارت، اتر اپراتور را از سیم ۱ می‌خواند حتی وقتی دیتا از سیم ۲ می‌رفت — پس حالت خودکار نقشه‌اش را برای شبکهٔ اشتباه می‌ساخت. حالا سیمی را می‌پرسد که واقعاً دیتا را حمل می‌کند. هیچ دسترسی تازه‌ای هم لازم نیست.</p>

<h2 dir="rtl" align="right">‏بیلد <span dir="ltr">x86_64</span></h2>

<p dir="rtl" align="right">‏برای کروم‌بوک و امولاتور. آنجا فایل <span dir="ltr">x86_64</span> را بردارید؛ برای گوشی همان <span dir="ltr">arm64-v8a</span> درست است.</p>

<h2 dir="rtl" align="right">‏اصلاح بیلد</h2>

<p dir="rtl" align="right">‏بیلد انتشار می‌توانست روی باینری‌ای که تور را <em>داشت</em> با پیام «این باینری پشتیبانی تور ندارد» شکست بخورد — یک پایپ‌لاین شل که کد خروج اشتباه گزارش می‌کرد. به خود برنامه ربطی ندارد، به این ربط دارد که اصلاً بشود نسخه‌ای منتشر کرد.</p>

<h2 dir="rtl" align="right">‏🔒 ممیزی امنیتی <span dir="ltr">1.4.0</span>: <strong>۹۲ از ۱۰۰</strong></h2>

<p dir="rtl" align="right">‏یه ممیزی تازهٔ صفر تا صد از کل برنامه گرفته شد و این بار بخش اشتراک روی شبکهٔ محلی هم جدا امتیاز گرفت. گزارش کامل: <span dir="ltr"><a href="../docs/SECURITY_AUDIT_1.4.0.md">docs/SECURITY_AUDIT_1.4.0.md</a></span>. نسخهٔ برنامه <span dir="ltr">1.4.0</span>، کد نسخه <span dir="ltr">16</span> و هستهٔ <span dir="ltr">2.1.0</span>. <strong>امضا دست نخورده</strong>، پس مستقیم روی ۱.۳.۰ نصب می‌شه و لازم نیست حذفش کنی.</p>

<table dir="rtl" style="width:100%; clear:both;">
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

<table dir="rtl" style="width:100%; clear:both;">
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

---

<div style="clear:both;"></div>

## 📢 Aether v1.4.0 Release Message (from the Developer)

Hello dear friends 🌹

Aether v1.4.0 has finally been released after about two weeks, with many changes. Version 1.4.0 was a major and time-consuming project, and most of the problems you reported have been fully fixed.

I personally tested most of the features, and their documentation is available on this page. However, due to limited time, I could not test two features. Friends who requested these features are kindly asked to test them and report the results in the tickets section:

- **Android TV compatibility:** I did not have time to test the app on a TV. Please test it on your Android TV and report the result.
- **Cloudflare Zero Trust:** I did not test this feature either. Please test it and provide feedback.

### 📡 Important Connection and Network Testing Notes

- I tested all six connection modes on AsiaTech and MCI networks, and all of them connected successfully. All six connections were tested in Smart mode with the default settings.
- The Tor → Aether connection problem that existed in version 1.3.0 has been fixed in version 1.4.0, and it now connects successfully.
- **Be patient during the first connection:** Internet and filtering conditions in Iran may change from hour to hour. The first connection—especially with combined modes such as Aether → Psiphon or Tor → Psiphon—may take between 1 and 3 minutes depending on the operator’s DPI conditions. Do not disconnect immediately or conclude that the app does not work.
- Subsequent connections usually take less than one minute. These times are based on my experience and testing on AsiaTech and MCI networks.
- If the speed or ping is not good on the first connection, disconnect once and connect again. Internet conditions in Iran are unstable, and reconnecting may resolve the problem.
- With some trial and error and the help of the AI inside the app, you can find the optimal configuration for your operator.

### 🤖 AI and Model Changes

- The app’s AI has been optimized and now provides more accurate answers, analyses, results, and recommendations.
- Some users reported reaching the daily limit very quickly. This is because they selected models 3.8, 3.7, or 3.5; these models reach their daily limits more quickly after only a few requests.
- For this reason, the default model is now set to `Gemini 3.1 Flash-Lite`, because it takes longer to reach its daily limit.
- **Important note about accessing AI websites:** Gemini and other restricted services such as ChatGPT can only be accessed through the two combined modes Aether → Psiphon and Tor → Psiphon. They will not open through the other connection modes because of restrictions.

Here is another important point to remember: last week, the Aether connection did not connect for me at all from morning until evening. Naturally, when Aether does not work, the combined Aether → Psiphon connection also cannot work, and it is not possible to use the app’s AI to troubleshoot the Aether connection.

However, the Tor → Psiphon connection was working for me. I connected through it and asked the app’s AI to examine the application logs and determine why Aether could not connect. After analyzing the logs, the AI provided optimized settings. Once I applied those settings, the Aether connection connected again.

Sometimes, severe filtering and connection problems must be resolved in this way: first connect through an alternative connection, then use the app’s AI to analyze the logs and find suitable settings. There is always a way to solve the problem.

### 🛠 Other Important Changes

- **User interface (UI):** The menus, submenus, and different sections of the app are now more professional, attractive, and organized.
- **VPN sharing:** This section has been completely rewritten and now includes two modes: home Wi-Fi and mobile hotspot. By following the in-app guide, you can set it up easily.
- In addition to these changes, many technical and security improvements were made, and numerous underlying bugs were fixed. You can read the full details in the documentation and change reports on this page.

### 💬 A Request to Everyone

Please do not use the tickets section only to report problems. If an issue has been fixed in this version or a connection works well for you, please also share your feedback so that we and other users can understand how the app is performing.

Thank you for your support ❤️

---

<div dir="rtl">

<div style="clear:both;"></div>

## 📢 پیام انتشار نسخه ۱.۴.۰ (از طرف توسعه‌دهنده)

درود به دوستان عزیز 🌹

بالاخره نسخه 1.4.0 بعد از حدود دو هفته، با کلی تغییرات منتشر شد. نسخه 1.4.0 واقعاً پروژه‌ای سنگین و زمان‌بر بود و بیشتر مشکلاتی که شما دوستان گزارش کرده بودید، به‌طور کامل برطرف شد.

بیشتر موارد را خودم تست کردم و مستندات آن‌ها در همین صفحه موجود است؛ می‌توانید آن‌ها را مطالعه کنید. فقط دو مورد را به دلیل کمبود وقت نتوانستم تست کنم. از دوستانی که این قابلیت‌ها را درخواست داده‌اند خواهش می‌کنم خودشان آن‌ها را تست کنند و نتیجه را در بخش تیکت‌ها اعلام کنند:

- **سازگاری با Android TV:** فرصت تست روی تلویزیون را نداشتم. لطفاً برنامه را روی Android TV خودتان تست کنید و نتیجه را اعلام کنید.
- **قابلیت Cloudflare Zero Trust:** این بخش را هم تست نکردم. لطفاً آن را بررسی کنید و فیدبک بدهید.

### 📡 نکات مهم اتصال و تست شبکه

- هر ۶ حالت اتصال را روی اینترنت‌های آسیاتک و همراه اول تست کردم و همه آن‌ها بدون مشکل متصل شدند. اتصال هر ۶ کانکشن با حالت هوشمند و با تنظیمات پیش‌فرض صورت گرفت.
- مشکل اتصال Tor → Aether که در نسخه 1.3.0 وجود داشت، در نسخه 1.4.0 برطرف شده و اکنون متصل می‌شود.
- **صبوری در اتصال اول:** همان‌طور که می‌دانید وضعیت اینترنت و فیلترینگ در ایران ممکن است هر ساعت تغییر کند. اتصال اول، مخصوصاً در حالت‌های ترکیبی مانند Aether → Psiphon یا Tor → Psiphon، بسته به شرایط DPI اپراتور ممکن است بین ۱ تا ۳ دقیقه زمان ببرد. بنابراین بلافاصله اتصال را قطع نکنید و نگویید که برنامه کار نمی‌کند.
- اتصال‌های بعدی معمولاً در کمتر از یک دقیقه برقرار می‌شوند. البته این زمان‌ها بر اساس تجربه و تست من روی اینترنت آسیاتک و همراه اول است.
- اگر در اتصال اول سرعت یا پینگ مناسبی نداشتید، کافی است یک بار اتصال را قطع و دوباره وصل کنید. وضعیت اینترنت در ایران پایدار نیست و ممکن است با اتصال مجدد مشکل برطرف شود.
- با کمی آزمون‌وخطا و کمک گرفتن از هوش مصنوعی داخل برنامه می‌توانید تنظیمات بهینه مناسب اپراتور خودتان را پیدا کنید.

### 🤖 تغییرات هوش مصنوعی و مدل‌ها

- هوش مصنوعی برنامه بهینه‌تر شده و اکنون پاسخ‌ها، تحلیل‌ها، نتایج و پیشنهادهای دقیق‌تری ارائه می‌دهد.
- بعضی از دوستان گفته بودند که خیلی سریع به سقف محدودیت روزانه می‌رسند. دلیل این موضوع استفاده از مدل‌های 3.8، 3.7 و 3.5 است؛ این مدل‌ها با چند درخواست سریع‌تر به سقف محدودیت روزانه خود می‌رسند.
- به همین دلیل، مدل پیش‌فرض را روی `Gemini 3.1 Flash-Lite` قرار دادیم، چون این مدل دیرتر به سقف محدودیت روزانه می‌رسد.
- **نکته بسیار مهم برای باز کردن سایت‌های هوش مصنوعی:** برای باز کردن سایت Gemini و سایر سرویس‌های تحریم‌شده مانند ChatGPT، فقط دو حالت ترکیبی Aether → Psiphon و Tor → Psiphon امکان‌پذیر هستند و روی سایر کانکشن‌ها، به دلیل تحریم‌ها، باز نخواهند شد.

یک نکته مهم دیگر را هم یادآوری کنم: هفته گذشته از صبح تا عصر، کانکشن Aether برای من اصلاً متصل نمی‌شد. طبیعتاً وقتی Aether کار نکند، کانکشن ترکیبی Aether → Psiphon هم کار نمی‌کند و نمی‌توان از هوش مصنوعی برنامه برای رفع مشکل کانکشن Aether کمک گرفت.

اما کانکشن Tor → Psiphon برای من کار می‌کرد. با استفاده از آن به هوش مصنوعی برنامه گفتم لاگ برنامه را بررسی کند و دلیل متصل نشدن کانکشن Aether را پیدا کند. هوش مصنوعی پس از بررسی لاگ‌ها، تنظیمات بهینه را برای من ارسال کرد و بعد از اعمال آن تنظیمات، کانکشن Aether دوباره متصل شد.

گاهی برای حل اختلالات شدید فیلترینگ و مشکلات اتصال برنامه باید از همین روش استفاده کنید؛ یعنی ابتدا با یک کانکشن جایگزین متصل شوید، سپس از هوش مصنوعی برنامه برای بررسی لاگ‌ها و پیدا کردن تنظیمات مناسب کمک بگیرید. همیشه راهی برای حل مشکل وجود دارد.

### 🛠 سایر تغییرات مهم

- **رابط کاربری (UI):** منوها، زیرمنوها و بخش‌های مختلف برنامه حرفه‌ای‌تر، زیباتر و منظم‌تر شده‌اند.
- **اشتراک‌گذاری VPN:** این بخش به‌طور کامل بازنویسی شده و اکنون دو حالت وای‌فای خانگی و هات‌اسپات موبایل را دارد. با دنبال کردن راهنمای داخل برنامه، می‌توانید به‌راحتی از آن استفاده کنید.
- علاوه بر این موارد، تغییرات فنی و امنیتی زیادی انجام شده و بسیاری از باگ‌های زیرساختی برطرف شده‌اند. جزئیات کامل را می‌توانید در مستندات و گزارش تغییرات همین صفحه مطالعه کنید.

### 💬 یک خواهش از دوستان

لطفاً در بخش تیکت‌ها فقط مشکلات را گزارش نکنید. اگر مشکلی در این نسخه برطرف شده یا یک کانکشن برای شما به‌خوبی کار می‌کند، حتماً فیدبک خود را هم ارسال کنید تا ما و سایر دوستان از وضعیت عملکرد برنامه مطلع شویم.

از همراهی شما ممنونم ❤️

</div>
