# AetherMobile v1.2.6

- **Engine upgraded:** bundled core is now 1.7.0 (was 1.6.0): routing rules matched on the name read from the first bytes, upstream-proxy chaining, automatic replacement of a refused device identity, and a WireGuard hunt that verifies several endpoints on distinct addresses.
- **New in the UI:** Upstream proxy (chaining), "match domain rules behind the tunnel" with a tunable wait window, and "replace a refused identity".
- **Silent patch-loss bug fixed:** the merge baseline shipped in 1.2.5 was the patched file, so the next automatic core upgrade would have dropped the app manual-range patch without warning. Patches are now marker-wrapped, the merge base is rebuilt from those markers offline, every merge is verified, and the failure is covered by a new offline test scenario (19 checks, all green).
- **Manual endpoint range now applies to MASQUE and gool too**, not only WireGuard, and accepts `188.114.96.0/24`, `188.114.96.x` or a bare address.
- **Redesigned home screen:** status, session timer, server IP, live speeds and the protocol/endpoint/latency row are now ONE unified glassmorphic card with an animated equaliser glow on its edge while connected, pinned to the Aether palette and drawn without costing a recomposition per frame.
- **Signing preserved:** version code 10; existing signing configuration unchanged, so it installs over 1.2.5 in place.

Version: AetherMobile 1.2.6, version code 10. Bundled core 1.7.0.

<div dir="rtl">

# AetherMobile نسخهٔ ۱.۲.۶

- **ارتقای موتور:** هستهٔ همراه اکنون 1.7.0 است (پیش‌تر 1.6.0): تطبیق قواعد مسیریابی روی نام خوانده‌شده از نخستین بایت‌ها، زنجیره‌سازی با پراکسی بالادست، جایگزینی خودکار شناسهٔ ردشده، و جستوجوی چند اندپوینت وایرگارد روی آدرس‌های متمایز.
- **تازه‌ها در رابط کاربری:** پراکسی بالادست (زنجیره‌سازی)، «تطبیق قواعد دامنه در پشت تونل» با پنجرهٔ انتظار قابل تنطیم، و «جایگزینی شناسهٔ ردشده».
- **رفع یک باگ بی‌صدا:** فایل مبنای ادغام که در ۱.۲.۵ منتشر شد همان فایل پچ‌خورده بود، پس ارتقای خودکار بعدی پچ محدودهٔ دستی را بی‌هیچ هشداری دور می‌انداخت. حالا پچ‌ها نشانه‌گذاری شده‌اند، مبنا آفلاین بازسازی می‌شود، هر ادغام راستی‌آزمایی می‌شود و سناریوی تازه‌ای این خرابی را پوشش می‌دهد (۱۹ بررسی، همه سبز).
- **محدودهٔ دستی اندپوینت** اکنون روی MASQUE و gool هم اعمال می‌شود، نه فقط وایرگارد.
- **بازطراحی صفحهٔ اصلی:** وضعیت، زمان‌سنج سشن، آی‌پی سرور، سرعت زنده و ردیف پروتکل/اندپوینت/تأخیر همه در یک کارت شیشه‌ای یکدست، با هالهٔ متحرک اکولایزری روی لبه در حالت متصل.
- **حفظ امضا:** version code برابر 10 است و تنطیمات امضا تغییر نکرده، پس روی ۱.۲.۵ در جای خود نصب می‌شود.

نسخه: AetherMobile 1.2.6، version code 10، هستهٔ همراه 1.7.0.

</div>
