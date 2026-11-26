package studio.cluvex.aether.core

import java.io.File

/**
 * Seals the ENGINE's identity files while the tunnel is down (audit 1.2.9-r3, F-3).
 *
 * ## What is in those files
 *
 * The native engine keeps its own state next to the app's files, written by the
 * Rust side (`aether/src/config.rs`):
 *
 *  * `aether.toml` / `aether-masque.toml` / `aether-team-<name>.toml` - the WARP
 *    device identity AND `wg_private_key`, the WireGuard **private key**, base64;
 *  * `aether-lastconn.toml` and friends - the last good gateway, i.e. exactly
 *    which endpoint this user was talking to;
 *  * `aether*.toml.corrupt` - a rejected copy of the same material, kept by the
 *    engine for diagnosis.
 *
 * That is the strongest single piece of identifying material the app has on disk,
 * and it was plaintext.
 *
 * ## Why sealing, and not "make the engine encrypt it"
 *
 * The engine is a separate process (a native executable exec'd from
 * nativeLibraryDir) and it has no access to the Android Keystore. Teaching it to
 * read a sealed blob means an engine-side key exchange and a vendored crypto path
 * in the core, which this app re-syncs from upstream on every build - a change
 * that would be silently reverted by the next `scripts/sync-core.sh`.
 *
 * So the app owns the file at rest and the engine owns it while running:
 *
 *  * [unsealInto] runs immediately BEFORE the engine is spawned. Each `*.sealed`
 *    blob is decrypted back to the exact filename the engine expects, with
 *    owner-only permissions.
 *  * [sealAndShred] runs immediately AFTER the engine has been reaped. Each
 *    identity file is sealed to `<name>.sealed` and the plaintext is shredded.
 *  * [sealIfIdle] runs at app startup and closes the crash case: if the process
 *    died without a clean stop, the plaintext is still there, and the next launch
 *    puts it away before anything else happens.
 *
 * The plaintext therefore exists only while the tunnel is actually up, which is
 * the window in which the key is in the engine's memory anyway. There is no way
 * to make it smaller without patching the core.
 *
 * ## Identity is preserved, which matters here
 *
 * Deleting the identity instead of sealing it would look "safer" and would cost
 * the user a fresh WARP enrolment on every connect: slower connects, a new device
 * handle each time (which is *more* identifying, not less, from the network's
 * point of view) and the loss of the quick-reconnect path to the last good
 * gateway. Sealing keeps the behaviour identical and removes the file.
 */
object IdentityVault {

    private const val TAG = "vault"
    private const val SEALED_SUFFIX = ".sealed"

    /**
     * Identity material written by the engine.
     *
     * Matches `aether.toml`, `aether-masque.toml`, `aether-team-acme.toml`,
     * `aether-lastconn.toml`, `aether-masque-lastconn.toml` and any `.corrupt`
     * sibling of those - i.e. every file the Rust `identity_path` /
     * `derive_sibling_path` pair can produce. Nothing else in `filesDir` is
     * touched: not `hev.yaml`, not `diagnostics.log`, not `psiphon/`.
     */
    private val IDENTITY = Regex("^aether(?:[A-Za-z0-9._-]*)\\.toml(?:\\.corrupt)?$")

    /**
     * 1.7.0 (same version) ROOT-CAUSE FIX - "stream did not contain valid UTF-8".
     *
     * Field log (IR-MCI, 2026-11-08): every engine start, of every protocol, in
     * the race and in the ladder, died 0.4 s after launch with
     * `Error: Io(InvalidData, "stream did not contain valid UTF-8")` - the
     * engine's `read_to_string` of an identity file. The file was not damaged by
     * the engine: two seal passes could run at the same time (a racing lane's
     * reap and the race's own sweep, or a second `stop()` of an engine that had
     * already been reaped and so decremented the counter of ANOTHER, live
     * engine). One pass shredded the plaintext with random bytes while the other
     * was reading it, and sealed that noise as the new blob. From then on every
     * session restored the noise, the engine died, and the noise was sealed
     * again - forever, for every protocol.
     *
     * Three guards, each sufficient on its own:
     *  * every vault operation holds [lock], so a seal can never read a file that
     *    another seal is shredding;
     *  * nothing that is not valid UTF-8 (the engine only ever writes TOML) is
     *    sealed or restored - such a copy is shredded instead, so the engine
     *    simply provisions a fresh identity;
     *  * [healCorrupt] removes every damaged copy when the engine reports it.
     */
    private val lock = Any()

    /** The engine's identity files are TOML, i.e. UTF-8 text. Anything else is damage. */
    internal fun isUtf8Text(bytes: ByteArray): Boolean = runCatching {
        java.nio.charset.StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes))
        // A NUL byte never appears in TOML written by the engine.
        bytes.none { it == 0.toByte() }
    }.getOrDefault(false)

    /**
     * Shreds every identity copy in [dir] (plaintext or sealed) that is not
     * valid UTF-8. Returns how many were removed. Safe at any time: a healthy
     * file is never touched.
     */
    fun healCorrupt(dir: File): Int = synchronized(lock) {
        var removed = 0
        dir.listFiles { f: File -> f.isFile }?.forEach { f ->
            val name = f.name
            when {
                IDENTITY.matches(name) -> {
                    val bytes = f.readBytesOrNull() ?: return@forEach
                    if (!isUtf8Text(bytes)) {
                        EncryptedLogFile.shred(f)
                        removed++
                    }
                }
                name.endsWith(SEALED_SUFFIX) && IDENTITY.matches(name.removeSuffix(SEALED_SUFFIX)) -> {
                    val plain = f.readBytesOrNull()?.let { KeyVault.open(it) }
                    if (plain == null || !isUtf8Text(plain)) {
                        EncryptedLogFile.shred(f)
                        removed++
                    }
                }
            }
        }
        if (removed > 0) {
            DiagnosticsLog.w(TAG, "Removed $removed damaged engine identity file(s); the engine provisions a fresh identity.")
        }
        removed
    }

    /** Restores every sealed identity file into [dir]. Call before spawning. */
    fun unsealInto(dir: File): Unit = synchronized(lock) { unsealLocked(dir) }

    private fun unsealLocked(dir: File) {
        val sealed = dir.listFiles { f: File ->
            f.isFile && f.name.endsWith(SEALED_SUFFIX) &&
                IDENTITY.matches(f.name.removeSuffix(SEALED_SUFFIX))
        } ?: return
        var restored = 0
        for (blobFile in sealed) {
            val target = File(dir, blobFile.name.removeSuffix(SEALED_SUFFIX))
            // A plaintext file that is already there wins: it is either newer
            // than the blob (the engine wrote it during a session that did not
            // shut down cleanly) or identical. Overwriting it with an older
            // sealed copy would roll the WARP identity back and force a
            // re-enrolment - the exact cost this class exists to avoid.
            if (target.exists()) {
                val current = target.readBytesOrNull()
                if (current != null && isUtf8Text(current)) continue
                // A damaged plaintext copy must not win over a good blob.
                DiagnosticsLog.w(TAG, "Identity ${target.name} is damaged on disk - restoring the sealed copy instead.")
                EncryptedLogFile.shred(target)
            }
            val plain = KeyVault.open(blobFile.readBytesOrNull() ?: continue)
            if (plain == null) {
                // Undecryptable: a keystore key that no longer exists (app data
                // restored onto another device, key invalidated). The engine will
                // simply provision a new identity, which is the correct
                // behaviour - so drop the blob instead of keeping something we
                // can never open again.
                DiagnosticsLog.w(TAG, "Sealed identity ${blobFile.name} cannot be opened on this device - discarding it; the engine will re-provision.")
                EncryptedLogFile.shred(blobFile)
                continue
            }
            if (!isUtf8Text(plain)) {
                DiagnosticsLog.w(TAG, "Sealed identity ${blobFile.name} holds damaged data - discarding it; the engine will re-provision.")
                EncryptedLogFile.shred(blobFile)
                continue
            }
            val ok = runCatching {
                target.writeBytes(plain)
                EncryptedLogFile.restrictToOwner(target)
                true
            }.getOrDefault(false)
            if (ok) restored++
        }
        if (restored > 0) {
            DiagnosticsLog.i(TAG, "Restored $restored sealed engine identity file(s) for this session.")
        }
    }

    /** Seals every plaintext identity file in [dir] and shreds the plaintext. */
    fun sealAndShred(dir: File): Unit = synchronized(lock) { sealLocked(dir) }

    private fun sealLocked(dir: File) {
        val plain = dir.listFiles { f: File -> f.isFile && IDENTITY.matches(f.name) } ?: return
        var sealed = 0
        var failed = 0
        var damaged = 0
        for (file in plain) {
            val bytes = file.readBytesOrNull() ?: continue
            if (!isUtf8Text(bytes)) {
                // Never seal damage: it would replace a good blob and make every
                // later engine start die on it. The older sealed copy (if any) is
                // kept; without one the engine provisions a fresh identity.
                EncryptedLogFile.shred(file)
                damaged++
                continue
            }
            val blob = KeyVault.seal(bytes)
            if (blob == null) {
                // No key: leaving the plaintext in place is strictly better than
                // deleting the user's WARP identity, so we keep it and say so
                // once. This is the only path on which the old behaviour remains.
                failed++
                continue
            }
            val ok = runCatching {
                val target = File(dir, file.name + SEALED_SUFFIX)
                target.writeBytes(blob)
                EncryptedLogFile.restrictToOwner(target)
                true
            }.getOrDefault(false)
            if (ok) {
                EncryptedLogFile.shred(file)
                sealed++
            } else {
                failed++
            }
        }
        if (sealed > 0) {
            DiagnosticsLog.i(TAG, "Sealed $sealed engine identity file(s) at rest (WireGuard key + WARP device identity).")
        }
        if (damaged > 0) {
            DiagnosticsLog.w(TAG, "$damaged damaged engine identity file(s) were discarded instead of sealed.")
        }
        if (failed > 0) {
            DiagnosticsLog.w(TAG, "$failed engine identity file(s) could NOT be sealed (device keystore unavailable) and stay in app-private storage.")
        }
    }

    /**
     * How many engines are running right now.
     *
     * A COUNTER rather than a flag, because a Smart Plus race has two engines
     * alive at once ([studio.cluvex.aether.core.SmartPlusPlan]). With a boolean,
     * the first lane to be reaped would set it false and seal the identity files
     * out from under the lane that is still connecting - and sealing shreds the
     * plaintext, so that is not a slow path, it is a broken session and a WARP
     * device that has to re-enrol.
     *
     * Guarded by [running] so [sealIfIdle] can never race a live engine: the
     * tunnel service runs in the same process, and pulling the identity file out
     * from under a connected engine would break the session it is describing.
     */
    fun sealIfIdle(dir: File) {
        if (running) return
        sealAndShred(dir)
    }

    /** True while at least one engine process is alive. */
    val running: Boolean
        get() = liveEngines.get() > 0

    private val liveEngines = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * Set by [AetherProcess] around each engine's lifetime.
     *
     * Returns the number of engines still alive afterwards, so the caller can seal
     * exactly when the last one is gone and not once per engine.
     */
    internal fun markRunning(alive: Boolean): Int =
        if (alive) {
            liveEngines.incrementAndGet()
        } else {
            liveEngines.updateAndGet { (it - 1).coerceAtLeast(0) }
        }

    private fun File.readBytesOrNull(): ByteArray? = runCatching { readBytes() }.getOrNull()
}
