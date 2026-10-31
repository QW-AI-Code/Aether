package studio.cluvex.aether.custom

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

/**
 * The three ways a config gets into the app - link, file, QR code - all ending
 * in [ConfigParser]. Everything that needs Android (assets, content URIs,
 * bitmaps, the network) lives here; the parsing itself does not.
 */
object ConfigImporter {

    private const val MAX_BYTES = 2 * 1024 * 1024
    private const val TIMEOUT_MS = 20_000

    @Volatile
    private var cachedTables: NpvtCodec.Tables? = null

    private val gen2Lock = Any()
    @Volatile private var cachedGen2: NpvtCodec.Tables? = null
    fun gen2Tables(context: Context): NpvtCodec.Tables = cachedGen2 ?: synchronized(gen2Lock) {
        cachedGen2 ?: context.assets.open("npvs/gen2-wb.bin").use {
            NpvtCodec.Tables.parse(it.readBytes())
        }.also { cachedGen2 = it }
    }

    /** The NapsternetV white-box tables, read once from `assets/npvt/wb.bin`. */
    fun tables(context: Context): NpvtCodec.Tables =
        cachedTables ?: synchronized(this) {
            cachedTables ?: context.assets.open("npvt/wb.bin").use { NpvtCodec.Tables.parse(it.readBytes()) }
                .also { cachedTables = it }
        }

    /**
     * Pasted or scanned text. An `https://` address is a subscription or a hosted
     * config file and is downloaded first; everything else is parsed as it is.
     */
    suspend fun fromText(
        context: Context,
        text: String,
        source: ConfigSource,
        password: String? = null,
    ): List<CustomConfig> = withContext(Dispatchers.IO) {
        val t = ConfigParser.cleanInput(text)
        if (ConfigParser.looksLikeMtProto(t)) throw ConfigImportException(ConfigParser.MTPROTO_UNSUPPORTED)
        val body = if (t.startsWith("https://", ignoreCase = true) || t.startsWith("http://", ignoreCase = true)) {
            if (t.lines().size > 1) t else return@withContext ConfigParser.parseBytes(
                download(t), source, { tables(context) }, { gen2Tables(context) }, password)
        } else {
            t
        }
        ConfigParser.parse(body, source, { tables(context) }, password)
    }

    /** A file picked with the system file picker: a text/JSON/npvt config, or a QR code image. */
    suspend fun fromFile(context: Context, uri: Uri, password: String? = null): List<CustomConfig> =
        withContext(Dispatchers.IO) {
            val mime = context.contentResolver.getType(uri).orEmpty()
            if (mime.startsWith("image/")) return@withContext fromImage(context, uri, password)
            val bytes = context.contentResolver.openInputStream(uri)?.use { readLimited(it) }
                ?: throw ConfigImportException("the file could not be opened")
            // An image with a generic MIME type (some file managers): try it as a QR code.
            if (isImage(bytes)) {
                val text = decodeQr(bytes) ?: throw ConfigImportException("no QR code found in the image")
                return@withContext ConfigParser.parse(text, ConfigSource.QR, { tables(context) }, password)
            }
            ConfigParser.parseBytes(bytes, ConfigSource.FILE, { tables(context) }, { gen2Tables(context) }, password)
        }

    /** An image holding a QR code (a screenshot, a photo of a screen, a saved picture). */
    suspend fun fromImage(context: Context, uri: Uri, password: String? = null): List<CustomConfig> =
        withContext(Dispatchers.IO) {
            val bytes = context.contentResolver.openInputStream(uri)?.use { readLimited(it, 16 * MAX_BYTES) }
                ?: throw ConfigImportException("the image could not be opened")
            val text = decodeQr(bytes) ?: throw ConfigImportException("no QR code found in the image")
            fromText(context, text, ConfigSource.QR, password)
        }

    /** Re-parses an edited config, keeping its identity, name and source. */
    suspend fun reparse(context: Context, old: CustomConfig, newRaw: String): CustomConfig =
        withContext(Dispatchers.IO) {
            val parsed = ConfigParser.parse(newRaw, old.source, { tables(context) }).first()
            parsed.copy(id = old.id, name = old.name, addedAt = old.addedAt,
                groupId = old.groupId, subscriptionId = old.subscriptionId)
        }

    // ------------------------------------------------------------- helpers

    private fun download(url: String): ByteArray {
        // The app is excluded from its own VPN, so a plain connection would fetch
        // the subscription on the bare network even while connected: the operator
        // would see (and could block) the subscription host. While a session runs
        // the request goes through the tunnel's local SOCKS5 port instead.
        val port = studio.cluvex.aether.vpn.AetherVpnService.activeForwardPort
        val target = URL(url)
        val opened = if (port in 1..65535) {
            target.openConnection(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port)))
        } else {
            target.openConnection()
        }
        val conn = (opened as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Aether")
            setRequestProperty("Accept", "*/*")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw ConfigImportException("the address answered HTTP $code")
            return conn.inputStream.use { readLimited(it) }
        } catch (e: ConfigImportException) {
            throw e
        } catch (e: Exception) {
            throw ConfigImportException("the address could not be downloaded (${e.javaClass.simpleName})")
        } finally {
            conn.disconnect()
        }
    }

    private fun readLimited(input: InputStream, limit: Int = MAX_BYTES): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > limit) throw ConfigImportException("the file is too large")
        }
        return out.toByteArray()
    }

    private fun isImage(b: ByteArray): Boolean =
        b.size > 8 && (
            (b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte()) || // PNG
                (b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()) || // JPEG
                (b[0] == 'R'.code.toByte() && b[8] == 'W'.code.toByte()) || // WEBP
                (b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte()) // GIF
            )

    /** Decodes the first QR code in an encoded image, or null. */
    fun decodeQr(bytes: ByteArray): String? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        return try {
            decodeQr(bmp)
        } finally {
            bmp.recycle()
        }
    }

    fun decodeQr(bmp: Bitmap): String? {
        val w = bmp.width
        val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        val source = RGBLuminanceSource(w, h, pixels)
        val hints = mapOf(
            DecodeHintType.TRY_HARDER to true,
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            DecodeHintType.CHARACTER_SET to "UTF-8",
        )
        val attempts = listOf(
            { BinaryBitmap(HybridBinarizer(source)) },
            { BinaryBitmap(GlobalHistogramBinarizer(source)) },
            { BinaryBitmap(HybridBinarizer(source.invert())) },
        )
        for (bitmap in attempts) {
            val reader = MultiFormatReader()
            val text = runCatching { reader.decode(bitmap(), hints).text }.getOrNull()
            if (!text.isNullOrBlank()) return text
        }
        return null
    }
}