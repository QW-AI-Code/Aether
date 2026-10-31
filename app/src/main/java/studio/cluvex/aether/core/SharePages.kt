package studio.cluvex.aether.core

/**
 * 1.4.0-r6: the pages and files the VPN share serves to other devices.
 *
 * Pure strings, no Android types, so they can be unit-tested and reviewed on
 * their own. Nothing here ever contains the share password: these pages are
 * answered to any device on the shared subnet, authenticated or not.
 *
 * ## Why a WebRTC "lock" is served at all (the r5 field report)
 *
 * A browser that is pointed at a proxy still sends WebRTC's STUN probes as
 * plain UDP on its OWN default route - an HTTP proxy cannot carry UDP, and
 * Chrome/Edge/Firefox only keep WebRTC inside a proxy when told to. Those probes
 * never reach this phone on home Wi-Fi (they go straight to the router), and on
 * a hotspot/USB they are forwarded by Android's tethering NAT, which an unrooted
 * app cannot filter. That is why `ipcheck.ing` showed the right IP for pages and
 * the real line IP under "WebRTC", while the phone's own browser (inside the
 * VPN) showed no leak.
 *
 * So the fix has two halves:
 *  - on the phone: every UDP datagram a client DOES hand the share (SOCKS5 UDP
 *    ASSOCIATE - v2rayN/v2rayNG/NekoBox/sing-box in TUN/VPN mode) goes through the
 *    tunnel, STUN included, and the bridge logs when it sees STUN pass;
 *  - on the guest: a one-click policy that switches the browser to
 *    `disable_non_proxied_udp` (Chrome, Edge, Brave, Chromium) or
 *    `proxy_only_if_behind_proxy` (Firefox). After that the browser either sends
 *    WebRTC through the proxy (TURN over TCP -> tunnel) or not at all; it can no
 *    longer reveal the real IP. The check page runs a live WebRTC test so the
 *    user can SEE which case they are in instead of trusting a label.
 */
object SharePages {

    /** A response body the bridge writes as-is. [filename] makes it a download. */
    class Page(
        val status: String,
        val contentType: String,
        val body: String,
        val filename: String? = null,
    )

    const val PATH_WINDOWS = "/webrtc/aether-webrtc-lock.reg"
    const val PATH_FIREFOX = "/webrtc/policies.json"
    const val PATH_MACOS = "/webrtc/aether-webrtc-lock-macos.sh"
    const val PATH_LINUX = "/webrtc/aether-webrtc-lock-linux.sh"

    /** Routes the download paths; null for anything else. */
    fun download(path: String): Page? = when (path.lowercase()) {
        PATH_WINDOWS -> Page("200 OK", "application/octet-stream", windowsReg(), "aether-webrtc-lock.reg")
        PATH_FIREFOX -> Page("200 OK", "application/json; charset=utf-8", firefoxPolicies(), "policies.json")
        PATH_MACOS -> Page("200 OK", "text/x-shellscript; charset=utf-8", macScript(), "aether-webrtc-lock-macos.sh")
        PATH_LINUX -> Page("200 OK", "text/x-shellscript; charset=utf-8", linuxScript(), "aether-webrtc-lock-linux.sh")
        else -> null
    }

    // ------------------------------------------------------------ lock files

    private const val POLICY_VALUE = "disable_non_proxied_udp"

    /**
     * Windows: Chrome and Chromium read `WebRtcIPHandling`, Edge reads
     * `WebRtcLocalhostIpHandling`; Brave uses the Chromium names. Both names are
     * written into every key - an unknown policy is ignored, so this is harmless
     * and covers each browser whichever name it honours. HKLM (all users, needs
     * the admin prompt) and HKCU (this user) are both set. CRLF, as regedit wants.
     */
    fun windowsReg(): String {
        val keys = listOf(
            "SOFTWARE\\Policies\\Google\\Chrome",
            "SOFTWARE\\Policies\\Microsoft\\Edge",
            "SOFTWARE\\Policies\\BraveSoftware\\Brave",
            "SOFTWARE\\Policies\\Chromium",
        )
        val sb = StringBuilder()
        sb.append("Windows Registry Editor Version 5.00\r\n\r\n")
        sb.append("; Aether VPN share - WebRTC lock.\r\n")
        sb.append("; Makes Chrome / Edge / Brave send WebRTC only through the configured proxy,\r\n")
        sb.append("; so it cannot reveal this computer's real IP. Restart the browser afterwards.\r\n")
        sb.append("; Undo: delete the WebRtcIPHandling / WebRtcLocalhostIpHandling values.\r\n\r\n")
        for (hive in listOf("HKEY_LOCAL_MACHINE", "HKEY_CURRENT_USER")) {
            for (key in keys) {
                sb.append('[').append(hive).append('\\').append(key).append("]\r\n")
                sb.append("\"WebRtcIPHandling\"=\"").append(POLICY_VALUE).append("\"\r\n")
                sb.append("\"WebRtcLocalhostIpHandling\"=\"").append(POLICY_VALUE).append("\"\r\n\r\n")
            }
        }
        return sb.toString()
    }

    /** Firefox `distribution/policies.json`: WebRTC only via the proxy, no host candidates. */
    fun firefoxPolicies(): String = """
        {
          "policies": {
            "Preferences": {
              "media.peerconnection.ice.proxy_only_if_behind_proxy": { "Value": true, "Status": "locked" },
              "media.peerconnection.ice.default_address_only": { "Value": true, "Status": "locked" },
              "media.peerconnection.ice.no_host": { "Value": true, "Status": "locked" }
            }
          }
        }
    """.trimIndent() + "\n"

    fun macScript(): String = """
        #!/bin/sh
        # Aether VPN share - WebRTC lock for macOS (Chrome, Edge, Brave).
        # Run:  sh aether-webrtc-lock-macos.sh   then quit and reopen the browser.
        defaults write com.google.Chrome WebRtcIPHandling -string $POLICY_VALUE
        defaults write com.microsoft.Edge WebRtcLocalhostIpHandling -string $POLICY_VALUE
        defaults write com.brave.Browser WebRtcIPHandling -string $POLICY_VALUE
        echo "WebRTC lock applied. Restart the browser, then open http://aether.check/ to verify."
    """.trimIndent() + "\n"

    fun linuxScript(): String = """
        #!/bin/sh
        # Aether VPN share - WebRTC lock for Linux (Chrome, Chromium, Brave, Edge).
        # Run:  sudo sh aether-webrtc-lock-linux.sh   then restart the browser.
        set -e
        for d in /etc/opt/chrome/policies/managed /etc/chromium/policies/managed \
                 /etc/brave/policies/managed /etc/opt/edge/policies/managed; do
          mkdir -p "${'$'}d"
          printf '{ "WebRtcIPHandling": "$POLICY_VALUE", "WebRtcLocalhostIpHandling": "$POLICY_VALUE" }\n' \
            > "${'$'}d/aether-webrtc-lock.json"
        done
        echo "WebRTC lock applied. Restart the browser, then open http://aether.check/ to verify."
    """.trimIndent() + "\n"

    // ----------------------------------------------------------------- pages
    //
    // 1.4.0-r10: the setup page (`http://<phone>:10811/`) and the check page
    // (`http://aether.check/`) were redesigned. What changed, and why:
    //
    //  - One dark navy layout for both pages, readable on a phone, a laptop and a
    //    TV browser. Plain CSS with hex colours (no oklch, no grid tricks that old
    //    TV browsers lack) because these pages are opened by ANY guest browser.
    //  - Bilingual with a switch at the top: Persian (default) and English. The
    //    choice is kept in the guest browser's localStorage only. Persian is
    //    right-to-left; every English word, address, port and file name inside a
    //    Persian sentence is an isolated left-to-right run, so it never scrambles
    //    the sentence around it.
    //  - Font: Vazirmatn / Vazir when the guest has it installed, otherwise the
    //    system's own Persian font. Only local() is used: the pages load nothing
    //    from the network (a web font fetch from the setup page would go out of
    //    the guest's own connection, before any proxy is set).
    //  - Step-by-step guidance for BOTH share modes (home Wi-Fi and phone hotspot),
    //    with the current mode opened and marked, plus per-device proxy steps.
    //  - Hardening: a Content-Security-Policy meta (no external loads, no forms,
    //    no base URL), no-referrer, every dynamic value HTML-escaped, and the
    //    WebRTC result is built from escaped text instead of raw candidate data.
    //
    // The live WebRTC test logic, the lock files and their paths are unchanged.

    /**
     * The check page. [via] names the path the request came in on (null = the
     * browser opened the phone directly, which proves reachability, not
     * protection). [exitIp] is the tunnel's verified exit, used by the live
     * WebRTC test to tell "through the tunnel" from "leaking". [mode] (r10) opens
     * the guide for the share mode the phone is in; null opens neither.
     */
    fun checkPage(
        via: String?,
        exitIp: String?,
        onDevice: Boolean,
        mode: ShareLeakGuard.Mode? = null,
    ): String {
        val protected = via != null
        val chip = if (protected) {
            stateChip("ok", "محافظت‌شده", "Protected")
        } else {
            stateChip("warn", "فقط در دسترس", "Reachable only")
        }
        val heading = if (protected) {
            h1(
                "این مرورگر از ${A} رد می‌شه",
                "This browser goes through Aether",
            )
        } else {
            h1(
                "گوشی در دسترسه، ولی مرورگرت هنوز ازش رد نمی‌شه",
                "The share is reachable, but this browser isn't using it yet",
            )
        }
        val lead = when {
            !protected -> para(
                "این صفحه رو مستقیم از گوشی باز کردی. یعنی دستگاهت به اشتراک می‌رسه، نه اینکه اینترنتش از " +
                    "اشتراک رد می‌شه. پروکسی رو تنظیم کن و بعد ${code("http://aether.check/")} رو باز کن.",
                "You opened the phone directly. That proves this device can reach the share, not that this " +
                    "browser uses it. Set the proxy, then open ${code("http://aether.check/")}.",
                "lead",
            )
            onDevice -> para(
                "این مرورگر روی خود گوشی داره از پروکسی ${A} استفاده می‌کنه.",
                "This browser is using the Aether proxy on the phone itself.",
                "lead",
            )
            else -> para(
                "صفحه‌های این مرورگر از اشتراک ${A} (از راه ${ltr(esc(via.orEmpty()))}) رد می‌شن و از تونل " +
                    "${ltr("VPN")} گوشی می‌رن بیرون، نه از اینترنت خودت.",
                "This browser's pages go through the Aether share (via ${esc(via.orEmpty())}) and leave " +
                    "through the phone's VPN tunnel, not your own connection.",
                "lead",
            )
        }
        val setupLink = if (protected) {
            ""
        } else {
            """<p class="actions"><a class="btn" href="/">${bi("راهنمای راه‌اندازی", "Setup guide")}</a></p>"""
        }
        return page(
            "بررسی اشتراک Aether",
            "Aether share check",
            """
            <section class="hero">
              $chip
              $heading
              $lead
              ${exitRow(exitIp)}
              $setupLink
            </section>
            ${webRtcSection(exitIp)}
            ${modeSection(mode)}
            """,
        )
    }

    /**
     * The setup page at `http://<phone>:10811/`. [mode] (r10) opens the guide for
     * the current share mode; [authRequired] (r10) says whether the share asks for
     * a username and password. The password itself is never part of any page.
     */
    fun setupPage(
        host: String,
        httpPort: Int,
        socksPort: Int,
        mode: ShareLeakGuard.Mode? = null,
        authRequired: Boolean? = null,
    ): String {
        val h = esc(host)
        val pac = "http://$h:$httpPort/proxy.pac"
        val modeChip = when (mode) {
            ShareLeakGuard.Mode.HOME_WIFI -> stateChip("info", "حالت: وای‌فای خونه", "Mode: home Wi-Fi")
            ShareLeakGuard.Mode.MOBILE_HOTSPOT -> stateChip("info", "حالت: هات‌اسپات گوشی", "Mode: phone hotspot")
            null -> ""
        }
        val authNote = when (authRequired) {
            true -> note(
                "ok",
                "رمز اشتراک روشنه. نام کاربری و رمز رو از کارت «اشتراک» تو برنامه بردار؛ این صفحه هیچ‌وقت رمز رو " +
                    "نشون نمی‌ده.",
                "Share authentication is on. Take the username and password from the Share card in the app; " +
                    "this page never shows the password.",
            )
            false -> note(
                "warn",
                "رمز اشتراک خاموشه، یعنی هر دستگاهی که تو همین شبکه‌ست می‌تونه از ${ltr("VPN")} تو استفاده کنه. " +
                    "تو شبکهٔ خونه مشکلی نیست، ولی تو وای‌فای عمومی از کارت «اشتراک» روشنش کن.",
                "Share authentication is off: any device on this network can use your VPN. Fine at home; on " +
                    "public Wi-Fi turn it on from the Share card.",
            )
            null -> ""
        }
        return page(
            "اشتراک VPN در Aether",
            "Aether VPN share",
            """
            <section class="hero">
              ${stateChip("ok", "اشتراک روشنه", "Sharing is on")} $modeChip
              ${h1("اینترنت ${ltr("VPN")} گوشی، روی بقیهٔ دستگاه‌ها", "Share this phone's VPN with your other devices")}
              ${para(
                "این صفحه از خود گوشی باز شده، پس این دستگاه به گوشی می‌رسه. حالا فقط پروکسی رو تنظیم کن تا " +
                    "اینترنتش از تونل ${ltr("VPN")} گوشی رد بشه.",
                "This page came straight from the phone, so this device can reach it. Set the proxy below and " +
                    "its traffic will leave through the phone's VPN tunnel.",
                "lead",
              )}
            </section>

            <section aria-labelledby="s-addr">
              ${h2("s-addr", "آدرس‌ها", "Addresses")}
              ${para(
                "ساده‌ترین راه آدرس ${ltr("PAC")} ـه: یه بار واردش کن، بقیه‌ش خودکاره. اگه ${ltr("VPN")} گوشی " +
                    "قطع بشه، این دستگاه هم آفلاین می‌شه و آی‌پی واقعیت لو نمی‌ره.",
                "PAC is the easiest: enter it once and the rest is automatic. If the phone's VPN drops, this " +
                    "device goes offline instead of leaking your real IP.",
                "sub",
              )}
              <div class="rows">
                ${addrRow(bi("پروکسی خودکار (${ltr("PAC")})", "Automatic proxy (PAC)"), pac, recommended = true)}
                ${addrRow(bi("پروکسی ${ltr("HTTP")}", "HTTP proxy"), "$h:$httpPort")}
                ${addrRow(
                  bi("پروکسی ${ltr("SOCKS5")}", "SOCKS5 proxy"),
                  "$h:$socksPort",
                  hint = bi("گزینهٔ ${ltr("Remote DNS")} رو روشن کن", "Turn Remote DNS on"),
                )}
              </div>
              $authNote
            </section>

            ${deviceSection()}
            ${modeSection(mode)}

            <section aria-labelledby="s-test">
              ${h2("s-test", "تست نهایی", "Final check")}
              ${para(
                "بعد از تنظیم پروکسی، ${code("http://aether.check/")} رو باز کن. این آدرس فقط وقتی باز می‌شه که " +
                    "واقعاً از اشتراک رد بشی؛ اگه باز نشد، پروکسی هنوز درست تنظیم نشده.",
                "After setting the proxy, open ${code("http://aether.check/")}. It only loads when you really " +
                    "go through the share; if it does not open, the proxy is not set yet.",
                "sub",
              )}
              <p class="actions">
                <a class="btn" href="http://${ShareLeakGuard.CHECK_HOST}/">${bi("باز کردن ${ltr("aether.check")}", "Open aether.check")}</a>
                <a class="btn ghost" href="/check">${bi("تست دسترسی از همین‌جا", "Test reachability from here")}</a>
              </p>
            </section>

            ${webRtcSection(null)}
            """,
        )
    }

    // ------------------------------------------------------------ page parts

    /** "Aether" as an isolated LTR run inside Persian text. */
    private val A = """<span class="ltr" dir="ltr">Aether</span>"""

    private fun ltr(text: String): String = """<span class="ltr" dir="ltr">$text</span>"""

    private fun code(text: String): String = """<code dir="ltr">$text</code>"""

    /** Both languages side by side; CSS shows the active one. */
    private fun bi(fa: String, en: String): String =
        """<span class="fa">$fa</span><span class="en" dir="ltr">$en</span>"""

    private fun para(fa: String, en: String, cls: String = ""): String =
        """<p class="fa $cls">$fa</p><p class="en $cls" dir="ltr">$en</p>"""

    private fun h1(fa: String, en: String): String =
        """<h1><span class="fa">$fa</span><span class="en" dir="ltr">$en</span></h1>"""

    private fun h2(id: String, fa: String, en: String): String =
        """<h2 id="$id"><span class="fa">$fa</span><span class="en" dir="ltr">$en</span></h2>"""

    private fun stateChip(kind: String, fa: String, en: String): String =
        """<span class="chip $kind"><i aria-hidden="true"></i>${bi(fa, en)}</span>"""

    private fun note(kind: String, fa: String, en: String): String =
        """<div class="note $kind">${para(fa, en)}</div>"""

    private fun steps(fa: List<String>, en: List<String>): String =
        """<ol class="steps fa">${fa.joinToString("") { "<li>$it</li>" }}</ol>""" +
            """<ol class="steps en" dir="ltr">${en.joinToString("") { "<li>$it</li>" }}</ol>"""

    private fun addrRow(label: String, value: String, recommended: Boolean = false, hint: String = ""): String {
        val badge = if (recommended) """<span class="badge">${bi("پیشنهادی", "Recommended")}</span>""" else ""
        val hintLine = if (hint.isEmpty()) "" else """<span class="hint">$hint</span>"""
        return """
            <div class="row">
              <div class="k">$label $badge</div>
              <div class="v"><code dir="ltr">$value</code>$hintLine</div>
              <button type="button" class="copy" data-copy="$value">${bi("کپی", "Copy")}</button>
            </div>
        """
    }

    private fun exitRow(exitIp: String?): String {
        val ip = exitIp?.let { sanitizeIp(it) }.orEmpty()
        if (ip.isEmpty()) return ""
        return """
            <div class="exit">
              <span class="k">${bi("آی‌پی خروجی تونل", "Tunnel exit IP")}</span>
              <code dir="ltr">$ip</code>
            </div>
        """
    }

    /** r10: per-device proxy steps, one expandable row per platform. */
    private fun deviceSection(): String {
        val pacFa = "آدرس ${ltr("PAC")}"
        return """
        <section aria-labelledby="s-dev">
          ${h2("s-dev", "تنظیم روی هر دستگاه", "Set it up on each device")}
          ${para(
            "دستگاهت رو باز کن و قدم‌ها رو برو. هر جا ${pacFa} خواست، همون آدرس بالا رو بچسبون.",
            "Open your device and follow the steps. Wherever it asks for a PAC or script address, paste the one above.",
            "sub",
          )}
          <div class="rows acc">
            ${device(
              "Windows",
              listOf(
                "${ltr("Settings")} ← ${ltr("Network &amp; Internet")} ← ${ltr("Proxy")} رو باز کن.",
                "زیر ${ltr("Automatic proxy setup")}، گزینهٔ ${ltr("Use setup script")} رو روشن کن.",
                "${pacFa} رو تو ${ltr("Script address")} بچسبون و ${ltr("Save")} رو بزن.",
              ),
              listOf(
                "Open Settings → Network &amp; Internet → Proxy.",
                "Under Automatic proxy setup, turn on Use setup script.",
                "Paste the PAC address into Script address and press Save.",
              ),
            )}
            ${device(
              "macOS",
              listOf(
                "${ltr("System Settings")} ← ${ltr("Wi-Fi")} ← ${ltr("Details")} ← ${ltr("Proxies")}.",
                "${ltr("Automatic proxy configuration")} رو روشن کن و ${pacFa} رو بچسبون.",
                "${ltr("OK")} رو بزن و مرورگر رو یه بار ببند و باز کن.",
              ),
              listOf(
                "System Settings → Wi-Fi → Details → Proxies.",
                "Turn on Automatic proxy configuration and paste the PAC address.",
                "Press OK and restart the browser once.",
              ),
            )}
            ${device(
              "Android",
              listOf(
                "تو تنظیمات ${ltr("Wi-Fi")} روی شبکه‌ای که بهش وصلی بزن (یا آیکون چرخ‌دنده) و برو تو بخش پروکسی.",
                "${ltr("Proxy Auto-Config")} رو انتخاب کن و ${pacFa} رو بچسبون.",
                "این فقط مرورگر و بعضی برنامه‌ها رو رد می‌کنه. برای همهٔ برنامه‌ها، ${ltr("v2rayNG")} یا " +
                  "${ltr("NekoBox")} رو با آدرس ${ltr("SOCKS5")} تو حالت ${ltr("VPN")} بزن.",
              ),
              listOf(
                "In Wi-Fi settings, tap the network you are on (or its gear icon) and open Proxy.",
                "Choose Proxy Auto-Config and paste the PAC address.",
                "That covers the browser and some apps. For every app, run v2rayNG or NekoBox with the SOCKS5 " +
                  "address in VPN mode.",
              ),
            )}
            ${device(
              "iPhone / iPad",
              listOf(
                "${ltr("Settings")} ← ${ltr("Wi-Fi")} ← دکمهٔ ${ltr("(i)")} کنار شبکه.",
                "پایین صفحه ${ltr("Configure Proxy")} ← ${ltr("Automatic")}.",
                "${pacFa} رو تو ${ltr("URL")} بچسبون و ${ltr("Save")} رو بزن.",
              ),
              listOf(
                "Settings → Wi-Fi → the (i) button next to the network.",
                "At the bottom: Configure Proxy → Automatic.",
                "Paste the PAC address into URL and tap Save.",
              ),
            )}
            ${device(
              "Firefox",
              listOf(
                "${ltr("Settings")} ← پایین صفحه ${ltr("Network Settings")} ← ${ltr("Settings…")}.",
                "${ltr("Automatic proxy configuration URL")} رو بزن و ${pacFa} رو بچسبون.",
                "اگه ${ltr("SOCKS5")} می‌زنی، تیک ${ltr("Proxy DNS when using SOCKS v5")} رو هم بزن.",
              ),
              listOf(
                "Settings → Network Settings (at the bottom) → Settings….",
                "Pick Automatic proxy configuration URL and paste the PAC address.",
                "If you use SOCKS5 instead, also tick Proxy DNS when using SOCKS v5.",
              ),
            )}
            ${device(
              "TV / " + "کل دستگاه",
              listOf(
                "رو تلویزیون، گوشی یا لپ‌تاپ مهمان ${ltr("v2rayNG")}، ${ltr("NekoBox")} یا ${ltr("sing-box")} رو نصب کن.",
                "یه کانفیگ ${ltr("SOCKS5")} با آدرس بالا بساز و ${ltr("Routing")} رو روی ${ltr("Global")} بذار (بدون استثنا).",
                "تو حالت ${ltr("VPN")} روشنش کن. این‌جوری همه‌چی، حتی ${ltr("WebRTC")} و ${ltr("UDP")}، از تونل رد می‌شه.",
              ),
              listOf(
                "On the TV, guest phone or laptop install v2rayNG, NekoBox or sing-box.",
                "Create a SOCKS5 profile with the address above and set routing to Global (no bypass).",
                "Start it in VPN mode. Then everything, WebRTC and UDP included, goes through the tunnel.",
              ),
              enTitle = "TV / whole device",
            )}
          </div>
        </section>
        """
    }

    private fun device(title: String, fa: List<String>, en: List<String>, enTitle: String = title): String {
        val t = if (enTitle == title) ltr(esc(title)) else bi(esc(title), esc(enTitle))
        return """
            <details class="item">
              <summary><span class="name">$t</span><i class="chev" aria-hidden="true"></i></summary>
              <div class="body">${steps(fa, en)}</div>
            </details>
        """
    }

    /** r10: guidance for both share modes; the current one is opened and marked. */
    private fun modeSection(mode: ShareLeakGuard.Mode?): String {
        fun current(m: ShareLeakGuard.Mode) =
            if (mode == m) """<span class="badge cur">${bi("حالت فعلی", "Current")}</span>""" else ""
        fun openAttr(m: ShareLeakGuard.Mode) = if (mode == m) " open" else ""
        val wifi = ShareLeakGuard.Mode.HOME_WIFI
        val hotspot = ShareLeakGuard.Mode.MOBILE_HOTSPOT
        return """
        <section aria-labelledby="s-mode">
          ${h2("s-mode", "وای‌فای خونه یا هات‌اسپات؟", "Home Wi-Fi or hotspot?")}
          ${para(
            "اشتراک دو حالت داره و تو برنامه، از کارت «اشتراک» عوضش می‌کنی. راهنمای هر دو اینجاست.",
            "Sharing has two modes, switched from the Share card in the app. Both are explained here.",
            "sub",
          )}
          <div class="rows acc">
            <details class="item"${openAttr(wifi)}>
              <summary><span class="name">${bi("وای‌فای خونه", "Home Wi-Fi")}</span>${current(wifi)}<i class="chev" aria-hidden="true"></i></summary>
              <div class="body">
                ${para(
                  "گوشی و این دستگاه هر دو به یه مودم وصلن و گوشی ${ltr("VPN")} رو با بقیه شریک می‌شه.",
                  "The phone and this device share one router, and the phone shares its VPN with the rest.",
                  "sub",
                )}
                ${steps(
                  listOf(
                    "تو ${A}، کارت «اشتراک» رو روی «وای‌فای خانگی» بذار و روشنش کن.",
                    "این دستگاه رو به همون وای‌فایی وصل کن که گوشی بهش وصله. شبکهٔ مهمان (${ltr("Guest")}) " +
                      "نباشه، چون معمولاً اونجا دستگاه‌ها همدیگه رو نمی‌بینن.",
                    "آدرس ${ltr("PAC")} رو از کارت «اشتراک» (یا صفحهٔ ${code("http://&lt;phone&gt;:10811/")}) " +
                      "تو تنظیمات پروکسی بزن.",
                    "آدرس گوشی ممکنه با ری‌استارت مودم عوض بشه؛ اگه یهو قطع شد، آدرس تازه رو از کارت «اشتراک» بردار.",
                  ),
                  listOf(
                    "In Aether, set the Share card to Home Wi-Fi and turn it on.",
                    "Join this device to the same Wi-Fi as the phone. Not a Guest network: devices there " +
                      "usually cannot see each other.",
                    "Enter the PAC address from the Share card (or from ${code("http://&lt;phone&gt;:10811/")}) " +
                      "in the proxy settings.",
                    "The phone's address can change when the router restarts; if it suddenly stops, take the " +
                      "new address from the Share card.",
                  ),
                )}
                ${note(
                  "warn",
                  "تو وای‌فای عمومی (کافه، دانشگاه، هتل) حتماً «رمز اشتراک» رو روشن کن. تو این حالت ${ltr("WebRTC")} " +
                    "مستقیم از مودم می‌ره بیرون و اصلاً به گوشی نمی‌رسه؛ برای همین قفل ${ltr("WebRTC")} لازمه.",
                  "On public Wi-Fi (cafés, campus, hotels) turn share authentication on. In this mode WebRTC " +
                    "goes straight out of the router and never reaches the phone, so the WebRTC lock is needed.",
                )}
              </div>
            </details>
            <details class="item"${openAttr(hotspot)}>
              <summary><span class="name">${bi("هات‌اسپات گوشی", "Phone hotspot")}</span>${current(hotspot)}<i class="chev" aria-hidden="true"></i></summary>
              <div class="body">
                ${para(
                  "گوشی با دیتای موبایل به اینترنت وصله و خودش هات‌اسپات (یا ${ltr("USB")} و بلوتوث) می‌ده.",
                  "The phone is online over mobile data and is itself the hotspot (or USB / Bluetooth tether).",
                  "sub",
                )}
                ${steps(
                  listOf(
                    "هات‌اسپات گوشی رو روشن کن، بعد تو ${A} کارت «اشتراک» رو روی «هات‌اسپات موبایل» بذار.",
                    "این دستگاه رو به هات‌اسپات وصل کن. با کابل ${ltr("USB")} یا بلوتوث هم کار می‌کنه.",
                    "هر راه اتصال آدرس خودشو داره؛ از کارت «اشتراک» آدرسی رو بردار که مال همون راهیه که باهاش وصلی.",
                    "اگه هات‌اسپات رو خاموش و روشن کنی، اشتراک خودش دوباره وصل می‌شه؛ لازم نیست کاری بکنی.",
                  ),
                  listOf(
                    "Turn the phone's hotspot on, then set the Share card in Aether to Mobile hotspot.",
                    "Join this device to the hotspot. A USB cable or Bluetooth works too.",
                    "Each link has its own address; take the one from the Share card that matches how you are connected.",
                    "Toggling the hotspot off and on re-binds the share by itself; nothing to do.",
                  ),
                )}
                ${note(
                  "warn",
                  "اینجا ${ltr("WebRTC")} از ${ltr("NAT")} خود اندروید رد می‌شه و برنامه بدون روت نمی‌تونه جلوشو بگیره. " +
                    "پس یا قفل ${ltr("WebRTC")} پایین رو نصب کن، یا رو دستگاه مهمان ${ltr("v2rayNG")} / " +
                    "${ltr("NekoBox")} رو تو حالت ${ltr("VPN")} بزن.",
                  "Here WebRTC goes through Android's own tethering NAT, which an unrooted app cannot filter. " +
                    "Install the WebRTC lock below, or run v2rayNG / NekoBox in VPN mode on the guest device.",
                )}
              </div>
            </details>
          </div>
        </section>
        """
    }

    private fun webRtcSection(exitIp: String?): String = """
        <section aria-labelledby="s-rtc">
          ${h2("s-rtc", "نشت ${ltr("WebRTC")}", "WebRTC leak")}
          <div id="rtc" class="box wait" role="status" aria-live="polite">
            <i aria-hidden="true"></i>
            <div class="msg">${bi("داریم ${ltr("WebRTC")} رو تست می‌کنیم…", "Testing WebRTC…")}</div>
          </div>
          ${lockSection()}
        </section>
        ${webRtcScript(exitIp)}
    """

    private fun lockSection(): String = """
        ${para(
          "مرورگرها ${ltr("WebRTC")} رو (برای تماس تصویری و این‌جور چیزها) بیرون از پروکسی می‌فرستن، مگه اینکه قفلش " +
            "کنی. قفل مناسب رو یه بار نصب کن، مرورگر رو کامل ببند و باز کن، بعد این صفحه رو رفرش کن.",
          "Browsers send WebRTC outside a proxy unless told not to. Install the lock once on this computer, " +
            "fully restart the browser, and reload this page.",
          "sub",
        )}
        <div class="rows">
          ${lockRow(
            "Windows · Chrome, Edge, Brave",
            PATH_WINDOWS, "aether-webrtc-lock.reg",
            "دوبار روش کلیک کن و ${ltr("Yes")} رو بزن.",
            "Double-click it and accept.",
          )}
          ${lockRow(
            "Firefox",
            PATH_FIREFOX, "policies.json",
            "بذارش تو یه پوشه به اسم ${code("distribution")} کنار ${code("firefox.exe")} " +
              "(تو مک: ${code("Firefox.app/Contents/Resources/distribution")}).",
            "Put it in a folder named ${code("distribution")} next to ${code("firefox.exe")} " +
              "(macOS: ${code("Firefox.app/Contents/Resources/distribution")}).",
          )}
          ${lockRow(
            "macOS · Chrome, Edge, Brave",
            PATH_MACOS, "aether-webrtc-lock-macos.sh",
            "تو ترمینال بزن: ${code("sh aether-webrtc-lock-macos.sh")}",
            "In Terminal: ${code("sh aether-webrtc-lock-macos.sh")}",
          )}
          ${lockRow(
            "Linux · Chrome, Chromium, Brave, Edge",
            PATH_LINUX, "aether-webrtc-lock-linux.sh",
            "تو ترمینال بزن: ${code("sudo sh aether-webrtc-lock-linux.sh")}",
            "In a terminal: ${code("sudo sh aether-webrtc-lock-linux.sh")}",
          )}
        </div>
        ${para(
          "نمی‌خوای چیزی نصب کنی؟ افزونهٔ رسمی ${ltr("WebRTC Network Limiter")} رو بریز و گزینهٔ " +
            "${ltr("Use my proxy server")} رو بزن. رو گوشی‌ها و کل دستگاه هم ${ltr("v2rayNG")} یا ${ltr("NekoBox")} " +
            "تو حالت ${ltr("VPN")} با آدرس ${ltr("SOCKS5")} و مسیریابی ${ltr("Global")} همه‌چی رو از تونل رد می‌کنه.",
          "No install: the official WebRTC Network Limiter extension, option \"Use my proxy server\". On phones or a " +
            "whole device, v2rayNG or NekoBox in VPN mode with the SOCKS5 address and Global routing sends " +
            "everything, WebRTC included, through the tunnel.",
          "sub tail",
        )}
    """

    private fun lockRow(title: String, path: String, file: String, fa: String, en: String): String = """
        <div class="row">
          <div class="k">${ltr(esc(title))}</div>
          <div class="v">${bi(fa, en)}</div>
          <a class="dl" href="$path" download="$file">${bi("دانلود", "Download")}</a>
        </div>
    """

    /**
     * Live WebRTC test. Gathers ICE candidates against two public STUN servers
     * and compares every public address WebRTC learns with the tunnel exit:
     * none -> WebRTC cannot reveal an address; all == exit -> WebRTC rides the
     * tunnel; anything else -> leak, shown with the address so the user sees it.
     */
    private fun webRtcScript(exitIp: String?): String =
        WEBRTC_JS.replace("@@EXIT@@", exitIp?.let { sanitizeIp(it) }.orEmpty())

    // r7: keep only the leading IP literal. The r6 version filtered characters
    // out of the whole value, so "1.2.3.4<script>" became "1.2.3.4c".
    private fun sanitizeIp(value: String): String = value.trim()
        .takeWhile { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' || it == '.' || it == ':' }
        .take(45)

    /** r10: HTML-escapes a value that did not come from this file. */
    private fun esc(value: String): String {
        val sb = StringBuilder(value.length + 8)
        for (c in value) {
            when (c) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&#39;")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun page(titleFa: String, titleEn: String, body: String): String = """
        <!doctype html>
        <html lang="fa" dir="rtl" data-lang="fa" data-title-fa="$titleFa" data-title-en="$titleEn">
        <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; img-src data:; base-uri 'none'; form-action 'none'">
        <meta name="referrer" content="no-referrer">
        <meta name="color-scheme" content="dark">
        <meta name="theme-color" content="#0a1026">
        <title>$titleFa</title>
        $LANG_BOOT
        <style>$CSS</style>
        </head>
        <body>
        <div class="wrap">
          <header class="top">
            <div class="brand">
              <span class="mark" aria-hidden="true"></span>
              <span class="bn" dir="ltr">Aether</span>
              <span class="bs">${bi("اشتراک VPN", "VPN share")}</span>
            </div>
            <div class="lang" role="group" aria-label="Language / زبان">
              <button type="button" data-set-lang="fa" aria-pressed="true" lang="fa">فارسی</button>
              <button type="button" data-set-lang="en" aria-pressed="false" lang="en">English</button>
            </div>
          </header>
          <main>
          ${body.trimIndent()}
          </main>
          <footer class="foot">
            ${para(
              "این صفحه هیچ‌وقت رمز اشتراک رو نشون نمی‌ده و از اینترنت چیزی بارگذاری نمی‌کنه. تست ${ltr("WebRTC")} " +
                "فقط با دو سرور ${ltr("STUN")} عمومی (گوگل و کلادفلر) حرف می‌زنه.",
              "This page never shows the share password and loads nothing from the internet. The WebRTC test " +
                "only talks to two public STUN servers (Google and Cloudflare).",
            )}
          </footer>
        </div>
        $UI_JS
        </body></html>
    """.trimIndent()

    /** Runs before first paint so an English reader never sees the Persian flash. */
    private val LANG_BOOT = """
        <script>
        (function () {
          try {
            if (localStorage.getItem('aether-lang') === 'en') {
              var r = document.documentElement;
              r.setAttribute('data-lang', 'en'); r.lang = 'en'; r.dir = 'ltr';
            }
          } catch (e) {}
        })();
        </script>
    """.trimIndent()

    private val CSS = """
        @font-face{font-family:AetherFa;font-weight:400;font-display:swap;
          src:local("Vazirmatn"),local("Vazirmatn Regular"),local("Vazirmatn-Regular"),local("Vazir"),local("Vazir Regular"),local("Vazir-Regular"),local("Vazir FD"),local("Vazirmatn FD")}
        @font-face{font-family:AetherFa;font-weight:700;font-display:swap;
          src:local("Vazirmatn Bold"),local("Vazirmatn-Bold"),local("Vazir Bold"),local("Vazir-Bold"),local("Vazirmatn")}
        :root{--bg:#0a1026;--bg2:#0d1531;--s1:#111b3b;--s2:#18244d;--line:#1f2d5a;--line2:#2b3c75;
          --tx:#e7ecf8;--mut:#a0acc9;--dim:#7482a8;--ac:#8fa8ff;--ac2:#c3d0ff;
          --ok:#46d3a0;--okbg:#0e2a2c;--okl:#1f5b50;--warn:#f1c35e;--warnbg:#241f14;--warnl:#5b4a22;
          --bad:#ff7d8e;--badbg:#2b1224;--badl:#6b2a40;--info:#8fa8ff;--infobg:#141f45;--infol:#2e4088}
        *{box-sizing:border-box}
        html{background:var(--bg);color-scheme:dark;-webkit-text-size-adjust:100%}
        body{margin:0;color:var(--tx);line-height:1.8;font-size:16px;
          font-family:AetherFa,Vazirmatn,Vazir,"Segoe UI",Tahoma,"SF Arabic","Geeza Pro","Noto Sans Arabic UI","Noto Sans Arabic","Noto Naskh Arabic",system-ui,sans-serif;
          background:radial-gradient(1200px 520px at 50% -180px,#15225280,transparent 70%),var(--bg);min-height:100vh}
        html[data-lang=en] body{line-height:1.65}
        html[data-lang=fa] .en,html[data-lang=en] .fa{display:none!important}
        .ltr{direction:ltr;unicode-bidi:isolate}
        code{direction:ltr;unicode-bidi:isolate;display:inline-block;font-family:ui-monospace,"SF Mono",Menlo,Consolas,"Roboto Mono",monospace;
          font-size:.9em;background:var(--s1);border:1px solid var(--line);border-radius:6px;padding:0 6px;line-height:1.7;word-break:break-all;color:var(--ac2)}
        a{color:var(--ac);text-decoration-thickness:1px;text-underline-offset:3px}
        a:hover{color:var(--ac2)}
        :focus-visible{outline:2px solid var(--ac);outline-offset:2px;border-radius:6px}
        .wrap{max-width:760px;margin:0 auto;padding:16px 20px 48px}
        .top{display:flex;align-items:center;justify-content:space-between;gap:12px;padding:6px 0 18px;border-bottom:1px solid var(--line)}
        .brand{display:flex;align-items:center;gap:10px;min-width:0}
        .mark{width:30px;height:30px;border-radius:9px;flex:none;background:var(--s2);border:1px solid var(--line2);position:relative}
        .mark::after{content:"";position:absolute;inset:8px;border-radius:50%;border:2px solid var(--ac);border-top-color:transparent;transform:rotate(-40deg)}
        .bn{font-weight:700;font-size:17px;letter-spacing:.3px}
        .bs{color:var(--dim);font-size:14px;white-space:nowrap}
        .lang{display:inline-flex;flex:none;border:1px solid var(--line2);border-radius:999px;padding:3px;background:var(--bg2)}
        .lang button{font:inherit;font-size:14px;line-height:1;border:0;background:transparent;color:var(--mut);padding:8px 14px;border-radius:999px;cursor:pointer;transition:background .25s cubic-bezier(.22,1,.36,1),color .25s}
        .lang button[lang=en]{font-family:system-ui,"Segoe UI",Roboto,sans-serif}
        .lang button[aria-pressed=true]{background:var(--s2);color:var(--tx);box-shadow:inset 0 0 0 1px var(--line2)}
        .lang button:hover{color:var(--tx)}
        .hero{padding:34px 0 30px}
        .chip{display:inline-flex;align-items:center;gap:8px;font-size:13.5px;line-height:1;padding:7px 12px;border-radius:999px;border:1px solid;margin:0 0 0 6px;vertical-align:middle}
        html[dir=ltr] .chip{margin:0 6px 0 0}
        .chip i{width:8px;height:8px;border-radius:50%;background:currentColor}
        .chip.ok{color:var(--ok);background:var(--okbg);border-color:var(--okl)}
        .chip.warn{color:var(--warn);background:var(--warnbg);border-color:var(--warnl)}
        .chip.info{color:var(--info);background:var(--infobg);border-color:var(--infol)}
        .chip.ok i{box-shadow:0 0 0 4px #46d3a022}
        h1{font-size:clamp(25px,5.2vw,34px);line-height:1.45;margin:18px 0 10px;font-weight:800;letter-spacing:-.2px}
        html[data-lang=en] h1{line-height:1.25;letter-spacing:-.5px}
        .lead{color:var(--mut);font-size:17px;max-width:62ch;margin:0}
        .exit{display:flex;flex-wrap:wrap;align-items:center;gap:10px;margin:20px 0 0}
        .exit .k{color:var(--dim);font-size:14px}
        .exit code{font-size:15px;padding:2px 10px}
        section{padding:28px 0 26px;border-top:1px solid var(--line)}
        .hero{border-top:0}
        h2{font-size:20px;line-height:1.5;margin:0 0 4px;font-weight:700}
        .sub{color:var(--mut);margin:0 0 16px;font-size:15.5px;max-width:68ch}
        .sub.tail{margin:16px 0 0;font-size:14.5px;color:var(--dim)}
        .rows{border:1px solid var(--line);border-radius:14px;background:var(--bg2);overflow:hidden}
        .row{display:grid;grid-template-columns:minmax(0,1fr) auto;grid-template-areas:"k b" "v b";gap:4px 14px;padding:14px 16px;align-items:center}
        .row+.row{border-top:1px solid var(--line)}
        .row .k{grid-area:k;font-size:14px;color:var(--mut);display:flex;align-items:center;flex-wrap:wrap;gap:8px}
        .row .v{grid-area:v;min-width:0;font-size:14.5px}
        .row .v code{font-size:15px;padding:2px 8px}
        .row .hint{display:block;color:var(--dim);font-size:13px;margin-top:4px}
        .row .copy,.row .dl{grid-area:b}
        .badge{font-size:12px;line-height:1;padding:5px 9px;border-radius:999px;background:var(--infobg);color:var(--ac2);border:1px solid var(--infol);white-space:nowrap}
        .badge.cur{background:var(--okbg);color:var(--ok);border-color:var(--okl)}
        .copy,.dl,.btn{font:inherit;font-size:14px;line-height:1;display:inline-flex;align-items:center;justify-content:center;gap:6px;
          padding:10px 14px;border-radius:10px;border:1px solid var(--line2);background:var(--s1);color:var(--tx);cursor:pointer;text-decoration:none;white-space:nowrap;
          transition:background .25s cubic-bezier(.22,1,.36,1),border-color .25s,color .25s}
        .copy:hover,.dl:hover,.btn:hover{background:var(--s2);border-color:#3a4f94;color:var(--tx)}
        .copy:active,.dl:active,.btn:active{transform:translateY(1px)}
        .copy.done{color:var(--ok);border-color:var(--okl);background:var(--okbg)}
        .btn{background:var(--ac);color:#0a1026;border-color:var(--ac);font-weight:700;padding:12px 18px}
        .btn:hover{background:var(--ac2);border-color:var(--ac2);color:#0a1026}
        .btn.ghost{background:transparent;color:var(--tx);border-color:var(--line2);font-weight:500}
        .btn.ghost:hover{background:var(--s1);color:var(--tx)}
        .actions{display:flex;flex-wrap:wrap;gap:10px;margin:20px 0 0}
        .note{border:1px solid;border-radius:12px;padding:12px 14px;margin:14px 0 0;font-size:14.5px}
        .note p{margin:0}
        .note.ok{background:var(--okbg);border-color:var(--okl)}
        .note.warn{background:var(--warnbg);border-color:var(--warnl)}
        .acc .item+.item{border-top:1px solid var(--line)}
        .acc summary{list-style:none;cursor:pointer;display:flex;align-items:center;gap:10px;padding:15px 16px;font-weight:600;user-select:none}
        .acc summary::-webkit-details-marker{display:none}
        .acc summary:hover{background:#ffffff05}
        .acc .name{flex:1;min-width:0}
        .chev{width:9px;height:9px;border-inline-end:2px solid var(--dim);border-bottom:2px solid var(--dim);transform:rotate(45deg);transition:transform .3s cubic-bezier(.22,1,.36,1);margin:0 4px 4px}
        .acc details[open]>summary .chev{transform:rotate(225deg);margin-bottom:-4px}
        .acc details[open]>summary{border-bottom:1px solid var(--line)}
        .acc .body{padding:16px 16px 18px}
        .acc .body .note{margin-top:4px}
        .steps{list-style:none;counter-reset:s;padding:0;margin:0 0 12px}
        .steps>li{counter-increment:s;position:relative;padding-inline-start:40px;margin:0 0 12px;font-size:15px}
        .steps>li::before{content:counter(s);position:absolute;inset-inline-start:0;top:2px;width:26px;height:26px;border-radius:50%;
          background:var(--s2);border:1px solid var(--line2);color:var(--ac2);display:flex;align-items:center;justify-content:center;font-size:13px;font-weight:700;line-height:1}
        html[data-lang=fa] .steps>li::before{content:counter(s,persian)}
        .box{display:flex;gap:12px;align-items:flex-start;border:1px solid;border-radius:12px;padding:14px 16px;margin:6px 0 18px;font-size:15px}
        .box .msg{min-width:0;overflow-wrap:anywhere}
        .box>i{flex:none;width:24px;height:24px;border-radius:50%;margin-top:2px;display:flex;align-items:center;justify-content:center;font-style:normal;font-weight:800;font-size:14px;line-height:1}
        .box.wait{background:var(--infobg);border-color:var(--infol)}
        .box.wait>i{border:2px solid var(--line2);border-top-color:var(--ac);animation:spin .9s linear infinite}
        .box.ok{background:var(--okbg);border-color:var(--okl)}
        .box.ok>i{background:var(--ok);color:#0a1026}
        .box.ok>i::before{content:"\2713"}
        .box.warn{background:var(--warnbg);border-color:var(--warnl)}
        .box.warn>i{background:var(--warn);color:#0a1026}
        .box.warn>i::before{content:"!"}
        .box.bad{background:var(--badbg);border-color:var(--badl)}
        .box.bad>i{background:var(--bad);color:#0a1026}
        .box.bad>i::before{content:"\2715"}
        .box b{color:var(--tx)}
        @keyframes spin{to{transform:rotate(360deg)}}
        .foot{border-top:1px solid var(--line);margin-top:8px;padding-top:18px;color:var(--dim);font-size:13.5px}
        .foot p{margin:0;max-width:70ch}
        @media (max-width:560px){
          .wrap{padding:12px 16px 40px}
          .bs{display:none}
          .hero{padding:26px 0 24px}
          .row{grid-template-columns:minmax(0,1fr);grid-template-areas:"k" "v" "b";gap:8px}
          .row .copy,.row .dl{justify-self:start;padding:9px 16px}
          .actions .btn{flex:1 1 100%}
        }
        @media (prefers-reduced-motion:reduce){*{transition:none!important;animation-duration:2s!important}}
    """.trimIndent()

    /** Language switch and copy buttons. Copy works on plain http (no secure context). */
    private val UI_JS = """
        <script>
        (function () {
          var root = document.documentElement;
          var buttons = document.querySelectorAll('[data-set-lang]');
          function setLang(l, save) {
            root.setAttribute('data-lang', l);
            root.lang = l;
            root.dir = l === 'fa' ? 'rtl' : 'ltr';
            document.title = root.getAttribute('data-title-' + l) || document.title;
            for (var i = 0; i < buttons.length; i++) {
              buttons[i].setAttribute('aria-pressed', buttons[i].getAttribute('data-set-lang') === l ? 'true' : 'false');
            }
            if (save) { try { localStorage.setItem('aether-lang', l); } catch (e) {} }
          }
          setLang(root.getAttribute('data-lang') === 'en' ? 'en' : 'fa', false);
          for (var i = 0; i < buttons.length; i++) {
            buttons[i].addEventListener('click', function () { setLang(this.getAttribute('data-set-lang'), true); });
          }
          function fallbackCopy(text) {
            var t = document.createElement('textarea');
            t.value = text; t.setAttribute('readonly', ''); t.style.position = 'fixed'; t.style.opacity = '0';
            document.body.appendChild(t); t.select();
            var ok = false;
            try { ok = document.execCommand('copy'); } catch (e) {}
            document.body.removeChild(t);
            return ok;
          }
          var copies = document.querySelectorAll('[data-copy]');
          for (var j = 0; j < copies.length; j++) {
            copies[j].addEventListener('click', function () {
              var b = this, text = b.getAttribute('data-copy');
              var saved = b.innerHTML;
              function flag(ok) {
                b.classList.add('done');
                b.innerHTML = ok ? '<span class="fa">کپی شد</span><span class="en">Copied</span>'
                                 : '<span class="fa">دستی کپی کن</span><span class="en">Copy by hand</span>';
                setTimeout(function () { b.classList.remove('done'); b.innerHTML = saved; }, 1600);
              }
              if (navigator.clipboard && window.isSecureContext) {
                navigator.clipboard.writeText(text).then(function () { flag(true); }, function () { flag(fallbackCopy(text)); });
              } else {
                flag(fallbackCopy(text));
              }
            });
          }
        })();
        </script>
    """.trimIndent()

    private val WEBRTC_JS = """
        <script>
        (function () {
          var exitIp = "@@EXIT@@";
          var box = document.getElementById('rtc');
          if (!box) return;
          function esc(s) {
            return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
              .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
          }
          function ips(list) {
            return list.map(function (ip) { return '<b dir="ltr" class="ltr">' + esc(ip) + '</b>'; }).join(', ');
          }
          function show(cls, en, fa) {
            box.className = 'box ' + cls;
            box.innerHTML = '<i aria-hidden="true"></i><div class="msg"><span class="fa">' + fa +
              '</span><span class="en" dir="ltr">' + en + '</span></div>';
          }
          var W = '<span class="ltr" dir="ltr">WebRTC</span>';
          if (!window.RTCPeerConnection) {
            show('ok', 'WebRTC is not available in this browser, so it cannot reveal your IP.',
              W + ' تو این مرورگر نیست، پس نمی‌تونه آی‌پیت رو لو بده.');
            return;
          }
          function isV6(ip) { return ip.indexOf(':') >= 0; }
          function isPublic(ip) {
            if (isV6(ip)) {
              var l = ip.toLowerCase();
              return !(l.indexOf('fe80') === 0 || l.indexOf('fc') === 0 || l.indexOf('fd') === 0 || l === '::1');
            }
            var p = ip.split('.').map(Number);
            if (p.length !== 4) return false;
            if (p[0] === 10 || p[0] === 127 || p[0] === 0) return false;
            if (p[0] === 172 && p[1] >= 16 && p[1] <= 31) return false;
            if (p[0] === 192 && p[1] === 168) return false;
            if (p[0] === 169 && p[1] === 254) return false;
            if (p[0] === 100 && p[1] >= 64 && p[1] <= 127) return false;
            return true;
          }
          var found = {}, finished = false, pc;
          try {
            pc = new RTCPeerConnection({ iceServers: [{ urls: ['stun:stun.l.google.com:19302', 'stun:stun.cloudflare.com:3478'] }] });
          } catch (e) {
            show('ok', 'WebRTC is blocked in this browser, so it cannot reveal your IP.',
              W + ' تو این مرورگر بسته‌ست، پس نمی‌تونه آی‌پیت رو لو بده.');
            return;
          }
          function done() {
            if (finished) return;
            finished = true;
            try { pc.close(); } catch (e) {}
            var all = Object.keys(found);
            var sameFamily = all.filter(function (ip) { return !exitIp || isV6(ip) === isV6(exitIp); });
            var leaks = sameFamily.filter(function (ip) { return ip !== exitIp; });
            var exitTag = '<b dir="ltr" class="ltr">' + esc(exitIp) + '</b>';
            if (all.length === 0) {
              show('ok', 'No WebRTC leak: WebRTC cannot reveal any public IP from this browser.',
                'نشتی نیست: ' + W + ' هیچ آی‌پی عمومی‌ای از این مرورگر لو نمی‌ده.');
            } else if (exitIp && leaks.length > 0) {
              show('bad', 'WebRTC LEAK: this browser reveals ' + ips(leaks) + ' (not the tunnel exit ' + exitTag +
                '). Install the lock below and restart the browser.',
                'نشت ' + W + '! این مرورگر آی‌پی ' + ips(leaks) + ' رو لو می‌ده (نه آی‌پی خروجی تونل). ' +
                'قفل پایین رو نصب کن و مرورگر رو کامل ببند و باز کن.');
            } else if (exitIp && sameFamily.length > 0) {
              show('ok', 'WebRTC goes through the tunnel: it only sees the exit ' + exitTag + '.',
                W + ' هم از تونل رد می‌شه و فقط آی‌پی خروجی ' + exitTag + ' رو می‌بینه.');
            } else {
              show('warn', 'WebRTC reveals ' + ips(all) + '. If that is not the exit IP shown in Aether, it is a ' +
                'leak: install the lock below.',
                W + ' این آدرس رو نشون می‌ده: ' + ips(all) + '. اگه با آی‌پی خروجی توی برنامهٔ ' +
                '<span class="ltr" dir="ltr">Aether</span> یکی نیست، یعنی نشت داره؛ قفل پایین رو نصب کن.');
            }
          }
          pc.onicecandidate = function (e) {
            if (!e.candidate) { done(); return; }
            var parts = (e.candidate.candidate || '').split(' ');
            var ip = parts[4];
            if (ip && ip.indexOf('.local') < 0 && isPublic(ip)) found[ip] = true;
          };
          try { pc.createDataChannel('aether'); } catch (e) {}
          pc.createOffer().then(function (o) { return pc.setLocalDescription(o); }).catch(function () { done(); });
          setTimeout(done, 7000);
        })();
        </script>
    """.trimIndent()
}
