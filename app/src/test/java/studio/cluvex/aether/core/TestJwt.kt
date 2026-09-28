package studio.cluvex.aether.core

import java.util.Base64

/** Shape-valid JWTs for the Zero Trust tests: base64url, unpadded, fake signature. */
internal object TestJwt {
    fun make(payload: String, header: String = """{"alg":"HS256","typ":"JWT"}"""): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        return enc.encodeToString(header.toByteArray()) + "." +
            enc.encodeToString(payload.toByteArray()) + "." +
            "c2lnbmF0dXJl"
    }
}
