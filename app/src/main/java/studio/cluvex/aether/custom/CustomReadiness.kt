package studio.cluvex.aether.custom

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException

/** No upload test, geo-IP dependency, UDP gate or unbounded retry for custom configs. */
object CustomReadiness {
    suspend fun check(port: Int, alive: () -> Boolean): Boolean {
        repeat(3) { attempt ->
            currentCoroutineContext().ensureActive()
            if (!alive()) return false
            val ok = withContext(Dispatchers.IO) {
                try {
                    RealDelay.requestThrough(port, "www.gstatic.com", 12_000, authenticated = false)
                    true
                } catch (_: IOException) { false }
            }
            currentCoroutineContext().ensureActive()
            if (ok) return true
            if (attempt < 2) delay(1_500)
        }
        return false
    }
}
