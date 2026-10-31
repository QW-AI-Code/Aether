package studio.cluvex.aether.ui.announcements

/**
 * The announcements shown in the "What's new" dialog and kept in
 * Settings → پیام‌ها و اطلاعیه‌ها.
 *
 * ## Inline markup used in the strings below
 *
 *  * `**…**`  strong (pure white, bold)
 *  * `*…*`    emphasis (italic)
 *  * `{…}`    a LEFT-TO-RIGHT island - the Compose equivalent of `<bdi>` /
 *             `<span dir="ltr">`. It is rendered between U+2066 LEFT-TO-RIGHT
 *             ISOLATE and U+2069 POP DIRECTIONAL ISOLATE, so English words,
 *             protocol names and chain arrows (`{Config → Psiphon}`) are laid out
 *             LTR internally while the brackets and punctuation around them keep
 *             the Persian paragraph's direction. EVERY Latin run goes in braces.
 *  * `\n`     a line break inside the same block (the HTML `<br>`).
 *
 * To publish news for a new version, add an [Announcement] at the TOP of [all].
 */
data class Announcement(
    /** The versionName this announcement belongs to, e.g. "1.6.0". */
    val version: String,
    /** Short title for the archive list. */
    val shortTitle: String,
    val blocks: List<AnnouncementBlock>,
)

sealed interface AnnouncementBlock {
    /** `<h2>` / `<h3>` in the accent colour. */
    data class Heading(val text: String, val large: Boolean = false) : AnnouncementBlock

    /** A paragraph; [note] is the smaller, muted `#94a3b8` hint style. */
    data class Paragraph(val text: String, val note: Boolean = false) : AnnouncementBlock

    /** `<ul>`. */
    data class Bullets(val items: List<String>) : AnnouncementBlock

    /** `<ol>`, numbered with Persian digits. */
    data class Numbered(val items: List<String>) : AnnouncementBlock

    /** The amber "important" box with the start-side (right, in RTL) border. */
    data class Warning(val text: String) : AnnouncementBlock
}

object Announcements {

    private val v160 = Announcement(
        version = "1.6.0",
        shortTitle = "تازه‌های نسخهٔ 1.6.0",
        blocks = listOf(
            AnnouncementBlock.Heading("تازه‌های نسخهٔ {1.6.0}", large = true),
            AnnouncementBlock.Bullets(listOf(
                "🚀 **هستهٔ اتر به نسخهٔ {2.4.0} به‌روزرسانی شد.**",
                "🧩 این ارتقا یعنی چه؟ هسته، موتور اصلی اتصال است و در این نسخه گزینه‌های بیشتری برای تنظیم دقیق رمزنگاری و ارتباط امن ({TLS}) در اختیار شماست؛ مثل انتخاب {SNI} و میزبان {API}، الگوریتم‌ها و کلیدهای {TLS 1.3}، فشرده‌سازی گواهی، {ALPS}، {ECH} و ابزارهای ضدتشخیص ({GREASE}، پدینگ و ترتیب افزونه‌ها). همچنین پشتیبانی از آدرس جدید {API} برای {WARP} و بررسی گواهی دروازه ({Verify gateway certificate}) اضافه شده و پایداری و سرعت برقراری اتصال بهتر شده است.",
                "📁 ورود فایل و لینک کانفیگ‌های {V2Ray} و نپستر، شامل فرمت جدید {NPVS}؛ بازکردن فایل از برنامه‌های دیگر فقط پس از تأیید شما ذخیره می‌شود.",
                "🗂 تب‌های همه، خودکار و سفارشی با امکان ویرایش نام؛ انتقال کانفیگ و حذف تب بدون حذف اطلاعات.",
                "📊 جدول تازه با پینگ و وضعیت کنار نام، منوی هر سرور، نوار اسکرول نمایان و رنگ‌بندی سبز، زرد و قرمز پینگ.",
                "🔗 اصلاح مسیرهای {Config → Aether} و {Aether → Config} و نمایش خروجی واقعی اتصال.",
                "⚡ انتخاب خودکار بهترین سرور با بازه‌های ۱ تا ۶۰ دقیقه، بازخورد فوری و گزینهٔ مستقل راستی‌آزمایی برای اتصال‌های اصلی و سفارشی.",
                "🎯 تأخیر واقعی با تست {HTTPS} مثل {v2rayNG}.",
                "🔍 قابلیت جدید اسکن آی‌پی تمیز به برنامه اضافه شد. دیگر نیازی به برنامه‌های جانبی اسکنر {IP} نیست و همه چیز به صورت خودکار انجام می‌شود. برای کانفیگ‌هایی که روی بستر کلودفلر (مثل پنل {BPB} و غیره) هستند، اگر پینگ سرور {V2Ray} بالا رفت یا از کار افتاد، می‌توانید با زدن آیکون ذره‌بین یا از طریق منوی سه‌نقطه و انتخاب «آی‌پی تمیز» در کنار هر سرور، اسکن را آغاز کنید. برنامه پس از پیدا کردن آی‌پی و پورت تمیز، به صورت خودکار آن را جایگزین کرده و به سرور اضافه می‌کند. همچنین با زدن دکمه «اسکن آی‌پی تمیز»، اسکن به ترتیب برای تمام سرورهای آن تب انجام می‌شود. **نکته مهم:** بسته به شرایط شبکه، سرعت اسکن متغیر است. اسکن باید حتماً در حالت {VPN} خاموش انجام شود. اگر پیامی مبنی بر پیدا نشدن {IP} مناسب نمایش داده شد، کاربر باید چند بار دیگر اسکن را تکرار کند. همچنین در تنظیمات بخش «آی‌پی تمیز»، کاربر می‌تواند سختی اسکن را تغییر دهد یا رنج {IP} دلخواه خود را وارد کند.",
                "🎛 دو گزینهٔ تازه در تنظیمات: «انیمیشن‌های صفحهٔ اصلی» برای خاموش‌کردن نور آبی و افکت‌های متحرک در گوشی‌های ضعیف‌تر، و «صدای اتصال و قطع اتصال» برای روشن یا خاموش‌کردن صدای کوتاه اتصال و قطع اتصال.",
                "💾 مدیریت پروفایل‌ها: حالا می‌توانید تنظیمات فعلی خود را با یک ضربه به‌عنوان «پروفایل جدید» ذخیره کنید و هر زمان که خواستید میان پروفایل‌ها جابه‌جا شوید. تنظیمات را از یک فایل وارد کنید، یا پروفایل انتخاب‌شده را به‌صورت «غیرمحرمانه» خروجی بگیرید و با دوستانتان به اشتراک بگذارید؛ در این خروجی اطلاعات حساس شما همراه نمی‌شود و می‌توانید بی‌نگرانی آن را دست‌به‌دست کنید.",
                "🤖 دستیار {Gemini} با راهنمای هوشمند کنار هر تنظیم، بدون افشای اطلاعات خصوصی کانفیگ."
            )),
            AnnouncementBlock.Warning("سالم‌بودن کانفیگ به معنی پشتیبانی آن از تمام زنجیره‌ها نیست؛ مسیرهای مبتنی بر {UDP} به پشتیبانی سرور نیاز دارند. ابتدا اتصال تکی را تست کنید.")
        ) + listOf(
            AnnouncementBlock.Heading("⏳ صبور بودن در اولین اتصال!"),
            AnnouncementBlock.Paragraph(
                "چه در ۶ اتصال اصلی برنامه و چه در حالت‌های زنجیره‌ای (به‌ویژه هر حالتی که **{Tor}** داخل آن است):",
            ),
            AnnouncementBlock.Bullets(
                listOf(
                    "⏱️ **اتصال بار اول ممکن است بین ۳ تا ۴ دقیقه طول بکشد!** چرا؟ چون تور و موتورها برای " +
                        "بار نخست باید مشخصات شبکه، دایرکتوری و رله‌ها را دریافت و کش (ذخیره) کنند. لطفاً دکمه " +
                        "را پشت‌سر‌هم نزنید و کمی صبور باشید.",
                    "⚡ **از دفعات دوم به بعد:** چون اطلاعات ذخیره شده، اتصال بسیار سریع‌تر (معمولاً " +
                        "**زیر ۱ دقیقه**) برقرار خواهد شد.",
                ),
            ),

            AnnouncementBlock.Heading("📶 تفاوت شدید اپراتورها و روش قلق‌گیری در ایران"),
            AnnouncementBlock.Paragraph(
                "شرایط فیلترینگ و شدت اختلالات {DPI} در ایران یکدست نیست؛ وضعیت اینترنت همراه اول با " +
                    "ایرانسل، مخابرات یا شرکت‌های خصوصی (مثل شاتل و آسیاتک) فرق دارد و حتی منطقه به منطقه یا " +
                    "شهر به شهر متفاوت است!",
            ),
            AnnouncementBlock.Bullets(
                listOf(
                    "✅ **تنظیمات شبکه:** شرایط اتصال در " +
                        "**همراه اول، آسیاتک و سایر اپراتورها** یکسان نیست؛ ممکن است در منطقه یا اپراتور شما نیاز " +
                        "به تغییرات باشد.",
                    // The settings page is really called «انتقال و ضد-DPI» in this
                    // app (R.string.section_transport), so the text points there.
                    "🛡️ **تنظیمات ضد {DPI}:** اگر در حالت عادی وصل نشدید، وارد تنظیمات «انتقال و ضد-{DPI}» " +
                        "برنامه شوید و روش‌های مبهم‌سازی را امتحان کنید.",
                ),
            ),

            AnnouncementBlock.Heading("🛠 اگر به هیچ وجه متصل نشدید چه کنید؟"),
            AnnouncementBlock.Numbered(
                listOf(
                    "✈️ **ریست آی‌پی و شبکه:** در گوشی، دستگاه را ۲ تا ۳ دقیقه روی **حالت پرواز " +
                        "({Airplane Mode})** بگذارید؛ در اینترنت خانگی، مودم وای‌فای را ۳ دقیقه کاملاً خاموش " +
                        "کرده و مجدداً روشن کنید تا {IP} شما تغییر کند.",
                    "🔄 **تست و خطا:** گزینه‌های مختلف {DPI} را در برنامه تغییر دهید یا کانفیگ‌های اختصاصی " +
                        "سالم دیگری را در زنجیره‌ها تست کنید.",
                    "🚪 **کلام آخر و واقع‌بینانه:** اگر تمام حالت‌ها، کانکشن‌های اختصاصی، تنظیمات {DPI} و " +
                        "تغییرات شبکه را انجام دادید و باز هم نتیجه نگرفتید، متأسفانه پروتکل‌ها روی دکل یا مرکز " +
                        "مخابراتی منطقهٔ شما دچار اختلال شدید زیرساختی هستند و در آن لحظه باید سراغ فیلترشکن‌ها " +
                        "و {VPN}‌های دیگر بروید.",
                ),
            ),
        ),
    )

    /** Newest first. */
    val all: List<Announcement> = listOf(v160)

    /** What the automatic dialog shows: this version's news, else the newest. */
    fun forVersion(versionName: String): Announcement =
        all.firstOrNull { it.version == versionName } ?: all.first()
}
