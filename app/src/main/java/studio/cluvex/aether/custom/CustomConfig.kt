package studio.cluvex.aether.custom

import studio.cluvex.aether.model.TransportBackend

/**
 * 1.5.0 CUSTOM CONFIGS: a user-supplied proxy configuration (V2Ray / Xray link,
 * Xray JSON, or a NapsternetV `.npvt` file) that the bundled Xray core
 * (`libxray.so`) runs, either on its own or chained with the app's own engines.
 *
 * Nothing in this file touches the existing connection modes: a custom config
 * only takes part in a session when the user switched it on in Settings ->
 * Custom configs, and the six [TransportBackend] modes keep working exactly as
 * before when it is off.
 */

/** How the config reached the app. Shown in the list; does not change behaviour. */
enum class ConfigSource { LINK, FILE, QR }

/** What the stored text is, so an edit can be re-parsed the same way. */
enum class ConfigFormat { URI, XRAY_JSON, NPVT }

/** The proxy protocol of the config's outbound. [short] is what the chain label shows. */
enum class ConfigProtocol(val short: String) {
    VLESS("VLESS"),
    VMESS("VMess"),
    TROJAN("Trojan"),
    SHADOWSOCKS("SS"),
    SOCKS("SOCKS"),
    HTTP("HTTP"),
    WIREGUARD("WireGuard"),
    HYSTERIA("Hysteria"),
    OTHER("Xray"),
    ;

    companion object {
        /** Maps an Xray outbound `protocol` value onto the family it belongs to. */
        fun fromXray(protocol: String?): ConfigProtocol = when (protocol?.lowercase()) {
            "vless" -> VLESS
            "vmess" -> VMESS
            "trojan" -> TROJAN
            "shadowsocks", "ss" -> SHADOWSOCKS
            "socks" -> SOCKS
            "http" -> HTTP
            "wireguard" -> WIREGUARD
            "hysteria", "hysteria2" -> HYSTERIA
            else -> OTHER
        }
    }
}

/**
 * One saved config.
 *
 * [outbound] is the ready-to-run Xray outbound object (JSON, without a tag): it
 * is computed once at import time, so a session never re-parses user text and a
 * config that does not parse can never be saved in the first place.
 *
 * [raw] is the text the user can edit (the share link, or the JSON). It is
 * EMPTY for a locked NapsternetV config: its author asked for the server details
 * to stay hidden, and this app honours that - a locked config can be renamed,
 * selected, chained and deleted, but its address and credentials are never
 * shown, edited or exported.
 */
data class CustomConfig(
    val id: String,
    val name: String,
    val protocol: ConfigProtocol,
    val format: ConfigFormat,
    val source: ConfigSource,
    val outbound: String,
    val raw: String = "",
    /** `host:port`, for the list. Empty when [locked]. */
    val server: String = "",
    val locked: Boolean = false,
    /** The message a NapsternetV author attached to the config, if any. */
    val note: String = "",
    /** Epoch millis after which the author's lock says the config is expired; 0 = never. */
    val expiresAt: Long = 0L,
    /** NapsternetV lock: the author allowed only mobile data for this config. */
    val mobileOnly: Boolean = false,
    val addedAt: Long = 0L,
    val groupId: String? = null,
    val subscriptionId: String? = null,
) {
    /** The hop name in a chain label: `VLESS`, `Trojan`, ... or `Npvt` for a NapsternetV config. */
    val chainLabel: String
        get() = if (format == ConfigFormat.NPVT) NPVT_LABEL else protocol.short

    fun isExpired(now: Long = System.currentTimeMillis()): Boolean = expiresAt in 1 until now

    companion object {
        const val NPVT_LABEL = "Npvt"
    }
}

/** Where the config sits in the chain. */
enum class ChainPlacement {
    /** The config alone: `you -> config -> internet`. */
    SOLO,

    /** The config is the FIRST hop and the engine dials through it: `config -> engine`. */
    FIRST,

    /** The config is the LAST hop and is dialled through the engine: `engine -> config`. */
    LAST,
}

/**
 * The engine part of a chain.
 *
 * Every value except [PSIPHON] is one of the existing [TransportBackend] modes,
 * run exactly as that mode runs today. [PSIPHON] is the engine's own
 * `--psiphon-only` mode (core 2.1.0), which the regular mode list does not offer
 * because without a custom config in front of or behind it, it is the same as
 * the retired single-hop Psiphon.
 */
enum class ChainBase(val backend: TransportBackend?) {
    AETHER(TransportBackend.AETHER),
    PSIPHON(null),
    TOR(TransportBackend.TOR),
    AETHER_PSIPHON(TransportBackend.AETHER_PSIPHON),
    AETHER_TOR(TransportBackend.AETHER_TOR),
    TOR_PSIPHON(TransportBackend.TOR_PSIPHON),
    TOR_AETHER(TransportBackend.TOR_AETHER),
    ;

    /** The engine pipeline as text, with the engine's transport in brackets where it belongs. */
    fun label(transport: String?): String = backend?.pipelineLabel(transport) ?: "Psiphon"
}

/**
 * Every chain the app can build, in the order the picker lists them.
 *
 * Single, double and triple chains. The config always sits at one END of the
 * chain: the engine runs Tor and Psiphon itself, behind its own listener, so a
 * config can be put in front of the whole engine (the engine's `--upstream`) or
 * behind its exit (Xray's `dialerProxy`), but not between two hops that live
 * inside one engine process.
 */
enum class ChainMode(val placement: ChainPlacement, val base: ChainBase?) {
    SOLO(ChainPlacement.SOLO, null),

    CONFIG_AETHER(ChainPlacement.FIRST, ChainBase.AETHER),
    AETHER_CONFIG(ChainPlacement.LAST, ChainBase.AETHER),
    CONFIG_PSIPHON(ChainPlacement.FIRST, ChainBase.PSIPHON),
    PSIPHON_CONFIG(ChainPlacement.LAST, ChainBase.PSIPHON),
    CONFIG_TOR(ChainPlacement.FIRST, ChainBase.TOR),
    TOR_CONFIG(ChainPlacement.LAST, ChainBase.TOR),

    CONFIG_AETHER_PSIPHON(ChainPlacement.FIRST, ChainBase.AETHER_PSIPHON),
    CONFIG_AETHER_TOR(ChainPlacement.FIRST, ChainBase.AETHER_TOR),
    CONFIG_TOR_PSIPHON(ChainPlacement.FIRST, ChainBase.TOR_PSIPHON),
    CONFIG_TOR_AETHER(ChainPlacement.FIRST, ChainBase.TOR_AETHER),
    AETHER_PSIPHON_CONFIG(ChainPlacement.LAST, ChainBase.AETHER_PSIPHON),
    AETHER_TOR_CONFIG(ChainPlacement.LAST, ChainBase.AETHER_TOR),
    TOR_PSIPHON_CONFIG(ChainPlacement.LAST, ChainBase.TOR_PSIPHON),
    TOR_AETHER_CONFIG(ChainPlacement.LAST, ChainBase.TOR_AETHER),
    ;

    /** Number of hops: 1, 2 or 3. */
    val hops: Int
        get() = when (base) {
            null -> 1
            ChainBase.AETHER, ChainBase.PSIPHON, ChainBase.TOR -> 2
            else -> 3
        }

    /**
     * The chain as the user reads it, e.g. `VLESS → Aether(WIREGUARD)` or
     * `Aether → Psiphon → Npvt`, in the order traffic leaves the phone.
     */
    fun label(config: String, transport: String? = null): String {
        val engine = base?.label(transport) ?: return config
        return when (placement) {
            ChainPlacement.FIRST -> "$config$ARROW$engine"
            ChainPlacement.LAST -> "$engine$ARROW$config"
            ChainPlacement.SOLO -> config
        }
    }

    /** Shorter forms of [label], for a narrow row: without the transport brackets, then with tight arrows. */
    fun compactLabels(config: String, transport: String? = null): List<String> {
        val full = label(config, transport)
        val plain = label(config, null)
        val tight = plain.replace(ARROW, TIGHT_ARROW)
        return listOf(full, plain, tight).distinct()
    }

    companion object {
        const val ARROW = " \u2192 "
        const val TIGHT_ARROW = "\u2192"

        fun fromStoredName(raw: String?): ChainMode =
            entries.firstOrNull { it.name == raw?.trim()?.uppercase() } ?: SOLO
    }
}

/** What a custom session runs: the config and the chain it sits in. */
data class CustomSelection(val config: CustomConfig, val mode: ChainMode)