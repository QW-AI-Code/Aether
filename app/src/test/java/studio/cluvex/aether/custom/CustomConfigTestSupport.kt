package studio.cluvex.aether.custom

import java.io.File
import java.util.Base64

/** Shared fixtures for the custom-config tests. No real server appears anywhere here. */
internal object CustomConfigTestSupport {

    /** The shipped white-box tables, read from the module's assets like the app reads them. */
    val tables: NpvtCodec.Tables by lazy {
        val candidates = listOf(
            File("src/main/assets/npvt/wb.bin"),
            File("app/src/main/assets/npvt/wb.bin"),
        )
        val file = candidates.first { it.isFile }
        NpvtCodec.Tables.parse(file.readBytes())
    }

    fun resource(name: String): String =
        requireNotNull(javaClass.classLoader?.getResource(name)) { "missing test resource $name" }
            .readText()

    /**
     * Builds an NPVT1 file the way NapsternetV does: three parts, each
     * `base64(nonce || AES-CTR(plaintext))`. CTR is symmetric, so the app's own
     * [NpvtCodec.Tables.ctr] encrypts as well as it decrypts.
     */
    fun npvt(servers: String, lock: String, version: Int = 1): String {
        fun part(plain: String, seed: Int): String {
            val nonce = ByteArray(16) { (it * 7 + seed).toByte() }
            val cipher = tables.ctr(nonce + plain.toByteArray(Charsets.UTF_8))
            return Base64.getEncoder().encodeToString(nonce + cipher)
        }
        return "NPVT1\n" + listOf(part(version.toString(), 1), part(servers, 2), part(lock, 3)).joinToString(",")
    }

    fun npvtServer(name: String, lock: String): String =
        "[{\"name\":\"$name\",\"address\":\"example.org:443\",\"type\":\"V2RAY\"," +
            "\"v2rayProfile\":{\"configType\":5,\"server\":\"example.org\",\"serverPort\":\"443\"," +
            "\"password\":\"$UUID\",\"network\":\"ws\",\"host\":\"cdn.example.org\"," +
            "\"path\":\"/ws\",\"security\":\"tls\",\"sni\":\"cdn.example.org\",\"fingerPrint\":\"chrome\"," +
            "\"insecure\":true},\"lockConfig\":$lock}]"

    const val UUID = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
    const val PBK = "JLfJZqtyFf3MgHBiZcxuvqmRscjrM5xmSBVaWPPsObE"

    /** The four shapes of the user's sample file, with made-up servers and keys. */
    const val REALITY_VISION =
        "vless://$UUID@192.0.2.10:443?security=reality&encryption=none&pbk=$PBK&headerType=none" +
            "&fp=chrome&type=tcp&flow=xtls-rprx-vision&sni=yahoo.com&sid=c39cc7310a#%40reality-one"
    const val TLS_VISION =
        "vless://user@vision.example.net:443?security=tls&alpn=h2&encryption=none&insecure=0" +
            "&headerType=none&fp=chrome&type=tcp&allowInsecure=0&flow=xtls-rprx-vision" +
            "&sni=vision.example.net#%40tls-vision"
    const val REALITY_FIREFOX =
        "vless://$UUID@198.51.100.7:2063?security=reality&encryption=none&pbk=$PBK&headerType=none" +
            "&fp=firefox&type=tcp&flow=xtls-rprx-vision&sni=example.de#%40reality-two"
    const val XHTTP_CDN =
        "vless://$UUID@203.0.113.9:443?mode=auto&path=%2F%40chan%2F%40chan&security=tls&alpn=h2" +
            "&encryption=none&insecure=0&host=front.example.net&fp=chrome&type=xhttp&allowInsecure=0" +
            "&sni=sni.example.com#%40xhttp"
}