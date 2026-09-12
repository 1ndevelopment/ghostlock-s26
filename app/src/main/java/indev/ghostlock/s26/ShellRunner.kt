package indev.ghostlock.s26

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import java.io.File

/**
 * Runs shell commands either through Shizuku (preferred: uid 2000 shell,
 * same SELinux context family as the README's `adb shell` flow) or via the
 * app's own process (fallback, often blocked by SELinux from LD_PRELOAD /
 * exec on /data/local/tmp).
 */
object ShellRunner {

    const val TMP = "/data/local/tmp"
    const val PRELOAD_PATH = "$TMP/preload.so"
    const val HELPER_PATH = "$TMP/cve-2026-43499-root"
    const val KSUD_PATH = "$TMP/ksud"
    const val BOOT_LOG = "$TMP/ghostlock-boot.log"

    data class Result(val exitCode: Int, val output: String)

    fun isShizukuAvailable(): Boolean = try {
        Shizuku.pingBinder()
    } catch (_: Exception) {
        false
    }

    fun shizukuPermission(): Boolean {
        return try {
            if (!isShizukuAvailable()) false
            else if (Shizuku.isPreV11()) true
            else Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }
    }

    fun requestShizukuPermission(code: Int) {
        try {
            if (!Shizuku.isPreV11()) Shizuku.requestPermission(code)
        } catch (_: Exception) { /* manager not installed */
        }
    }

    /**
     * Requests Shizuku permission and suspends until the user answers (or the
     * timeout elapses). Returns PERMISSION_GRANTED / PERMISSION_DENIED.
     * Returns immediately when permission is already held or Shizuku is down.
     * Lets the Root flow wait for the grant instead of bailing out.
     */
    suspend fun requestShizukuPermissionAndWait(
        code: Int = 1001,
        timeoutMs: Long = 60_000,
    ): Int = withContext(Dispatchers.Main) {
        if (shizukuPermission()) return@withContext PackageManager.PERMISSION_GRANTED
        if (!isShizukuAvailable()) return@withContext PackageManager.PERMISSION_DENIED
        val deferred = CompletableDeferred<Int>()
        val listener = Shizuku.OnRequestPermissionResultListener { _, result ->
            deferred.complete(result)
        }
        Shizuku.addRequestPermissionResultListener(listener)
        try {
            Shizuku.requestPermission(code)
            withTimeoutOrNull(timeoutMs) { deferred.await() }
                ?: PackageManager.PERMISSION_DENIED
        } finally {
            Shizuku.removeRequestPermissionResultListener(listener)
        }
    }

    /** `sh -c <script>` through Shizuku or locally, capturing merged output. */
    suspend fun sh(
        script: String,
        extraEnv: Map<String, String> = emptyMap(),
        preferShizuku: Boolean = true,
        onLine: ((String) -> Unit)? = null,
    ): Result = withContext(Dispatchers.IO) {
        if (preferShizuku && shizukuPermission()) {
            runShizuku(script, extraEnv, onLine)
        } else {
            runLocal(script, extraEnv, onLine)
        }
    }

    /**
     * The exploit entrypoint. preload.so's constructor runs the full chain
     * and _exit()s, so the `sh` process never interprets anything — its
     * stdout IS the exploit log. Must be exactly:
     *   env LD_PRELOAD=/data/local/tmp/preload.so sh
     */
    suspend fun exploitAttempt(
        bootForce: Boolean,
        debug: Boolean,
        preferShizuku: Boolean = true,
        onLine: ((String) -> Unit)? = null,
    ): Result = withContext(Dispatchers.IO) {
        val env = mutableMapOf("LD_PRELOAD" to PRELOAD_PATH)
        if (bootForce) env["BOOT_FORCE"] = "1"
        if (debug) env["DEBUG"] = "1"
        // Mirror README: `env LD_PRELOAD=... sh` with no -c script.
        if (preferShizuku && shizukuPermission()) {
            runShizukuDirect(env, onLine)
        } else {
            runLocalDirect(env, onLine)
        }
    }

    // ---- Shizuku ----

    /**
     * Shizuku.newProcess is not public in the 13.x API artifact, so invoke it
     * reflectively (declared method, forced accessible).
     * Signature: newProcess(String[] cmd, String[] env, String dir),
     * where env entries are "K=V" strings.
     * Returns null when the binder is unavailable.
     */
    private fun shizukuProcess(cmd: Array<String>, env: Array<String>, dir: String): Process? {
        return try {
            val cls = Class.forName("rikka.shizuku.Shizuku")
            val m = cls.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java,
            )
            m.isAccessible = true
            m.invoke(null, cmd, env, dir) as? Process
        } catch (_: Exception) {
            null
        }
    }

    private fun runShizuku(
        script: String,
        extraEnv: Map<String, String>,
        onLine: ((String) -> Unit)?,
    ): Result {
        val env = (System.getenv().toMutableMap() + extraEnv)
            .map { (k, v) -> "$k=$v" }.toTypedArray()
        val proc = shizukuProcess(arrayOf("sh", "-c", script), env, TMP)
            ?: return Result(127, "Shizuku.newProcess unavailable (binder down?)")
        return drain(proc, onLine)
    }

    private fun runShizukuDirect(
        extraEnv: Map<String, String>,
        onLine: ((String) -> Unit)?,
    ): Result {
        val env = (System.getenv().toMutableMap() + extraEnv)
            .map { (k, v) -> "$k=$v" }.toTypedArray()
        val proc = shizukuProcess(arrayOf("sh"), env, TMP)
            ?: return Result(127, "Shizuku.newProcess unavailable (binder down?)")
        return drain(proc, onLine)
    }

    // ---- Local fallback ----

    private fun runLocal(
        script: String,
        extraEnv: Map<String, String>,
        onLine: ((String) -> Unit)?,
    ): Result {
        val pb = ProcessBuilder("sh", "-c", script)
            .directory(File(TMP))
            .redirectErrorStream(true)
        pb.environment().putAll(extraEnv)
        return try {
            drain(pb.start(), onLine)
        } catch (e: Exception) {
            Result(127, "local exec failed: ${e.message}")
        }
    }

    private fun runLocalDirect(
        extraEnv: Map<String, String>,
        onLine: ((String) -> Unit)?,
    ): Result {
        val pb = ProcessBuilder("sh")
            .directory(File(TMP))
            .redirectErrorStream(true)
        pb.environment().putAll(extraEnv)
        // Safety: never leak a host LD_PRELOAD into the child twice; we set it fresh.
        pb.environment()["LD_PRELOAD"] = PRELOAD_PATH
        return try {
            drain(pb.start(), onLine)
        } catch (e: Exception) {
            Result(127, "local exec failed: ${e.message}")
        }
    }

    private fun drain(proc: Process, onLine: ((String) -> Unit)?): Result {
        val sb = StringBuilder()
        proc.inputStream.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                sb.appendLine(line)
                onLine?.invoke(line)
            }
        }
        val rc = try {
            proc.waitFor()
        } catch (e: InterruptedException) {
            proc.destroyForcibly()
            -1
        }
        // Anything on stderr that wasn't merged (Shizuku path keeps them split).
        try {
            proc.errorStream.bufferedReader().readText().takeIf { it.isNotBlank() }?.let {
                sb.append(it)
                onLine?.invoke(it)
            }
        } catch (_: Exception) { }
        return Result(rc, sb.toString())
    }

    // ---- Staging ----

    /**
     * Streams the bundled payloads into /data/local/tmp and chmods them.
     *
     * Each payload is piped through the shell process's stdin (`cat > dst`):
     * the shell uid cannot read app-private files, so a temp-file handoff
     * (extract to filesDir, then `cat` from there) fails with EACCES under
     * Shizuku. Reading the asset in the app and streaming it avoids any
     * cross-uid file access. Tries the Shizuku shell first so ownership and
     * SELinux context match the adb-push flow.
     */
    suspend fun stage(
        context: Context,
        preferShizuku: Boolean = true,
        onLine: ((String) -> Unit)? = null,
    ): Result = withContext(Dispatchers.IO) {
        val useShizuku = preferShizuku && shizukuPermission()
        val log: (String) -> Unit = { onLine?.invoke(it) }

        val payloads = payloadSources(context, log)
        for ((src, dst, mode) in payloads) {
            log("staging $src → $dst")
            val script = "set -e; mkdir -p $TMP; rm -f '$dst'; cat > '$dst'; chmod $mode '$dst'"
            val proc = startShell(script, useShizuku)
                ?: return@withContext Result(127, "no shell channel available")
            try {
                val inp = if (src.startsWith("/")) File(src).inputStream()
                else context.assets.open(src)
                inp.use { it.copyTo(proc.outputStream) }
            } catch (e: Exception) {
                proc.destroyForcibly()
                return@withContext Result(127, "failed to read payload $src: ${e.message}")
            }
            proc.outputStream.close()
            val rc = proc.waitFor()
            if (rc != 0) {
                val err = try { proc.errorStream.bufferedReader().readText() } catch (_: Exception) { "" }
                return@withContext Result(
                    rc,
                    err.ifBlank { "staging $src failed (exit $rc)" } +
                        "\nSTAGE FAILED. Start Shizuku and grant permission, or " +
                        "adb-push the files per the README and press 'Check root'."
                )
            }
        }

        val r = sh("ls -l $PRELOAD_PATH $HELPER_PATH $KSUD_PATH || true", preferShizuku = useShizuku, onLine = onLine)
        if (r.exitCode != 0) {
            return@withContext Result(
                r.exitCode,
                r.output + "\nSTAGE FAILED. Start Shizuku and grant permission, or " +
                    "adb-push the files per the README and press 'Check root'."
            )
        }
        r
    }

    /** Start `sh -c <script>` through the chosen channel. */
    private fun startShell(script: String, useShizuku: Boolean): Process? {
        return if (useShizuku) {
            val env = System.getenv().map { (k, v) -> "$k=$v" }.toTypedArray()
            shizukuProcess(arrayOf("sh", "-c", script), env, TMP)
        } else {
            try {
                ProcessBuilder("sh", "-c", script).directory(File(TMP)).start()
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * Payload sources as (asset name OR absolute file path, tmp destination,
     * chmod mode). File paths are used for the CMake-built libpreload.so
     * fallback; everything else is read straight from the APK assets.
     */
    private fun payloadSources(
        context: Context,
        log: (String) -> Unit,
    ): List<Triple<String, String, String>> {
        val out = mutableListOf<Triple<String, String, String>>()

        fun add(assetOrPath: String, dst: String, mode: String, required: Boolean): Boolean {
            val ok = try {
                if (assetOrPath.startsWith("/")) {
                    val f = File(assetOrPath)
                    if (f.isFile) {
                        log("payload $assetOrPath (${f.length()} bytes)")
                        true
                    } else false
                } else {
                    context.assets.open(assetOrPath).use { inp ->
                        log("payload $assetOrPath (${inp.available()} bytes)")
                        true
                    }
                }
            } catch (e: Exception) {
                log("missing: $assetOrPath (${e.message})")
                false
            }
            if (ok) out += Triple(assetOrPath, dst, mode)
            else if (required) throw IllegalStateException("missing required payload: $assetOrPath")
            return ok
        }

        // ksud ships prebuilt upstream (4.9MB ARM64 PIE). Required: the root
        // stage execs it via late-load; without it you get a root shell but no KernelSU.
        add("ksud", KSUD_PATH, "755", required = false)

        // Built by stage-assets.sh (NDK r26+) into app/src/main/assets/:
        //   assets/preload.so, assets/su_daemon
        val hasPreload = add("preload.so", PRELOAD_PATH, "644", required = false)
        val hasHelper = add("su_daemon", HELPER_PATH, "755", required = false)

        // Fallback: CMake-built libpreload.so inside the APK (same sources).
        if (!hasPreload) {
            findNativePreload(log)?.let { lib ->
                out += Triple(lib, PRELOAD_PATH, "644")
                log("using CMake-built ${lib.substringAfterLast('/')} as preload.so")
            }
        }
        if (!hasHelper) {
            log("su_daemon asset missing — run exploit/stage-assets.sh with the NDK, " +
                "or adb-push a Makefile-built su_daemon_aarch64_pie to $HELPER_PATH first.")
        }
        return out
    }

    /** Locate libpreload.so unpacked from this APK (extractNativeLibs=true). */
    private fun findNativePreload(log: (String) -> Unit): String? {
        // Standard path when the CMake target above is enabled.
        val candidates = listOf(
            "/data/app/indev.ghostlock.s26*/lib/arm64/libpreload.so",
            "/data/app/*/indev.ghostlock.s26*/lib/arm64/libpreload.so",
        )
        // Direct check via nativeLibraryDir is done by the caller when available;
        // here do a cheap glob over /data/app (may fail under SELinux — non-fatal).
        return try {
            val proc = ProcessBuilder("sh", "-c", "ls ${candidates.joinToString(" ")} 2>/dev/null | head -n 1")
                .redirectErrorStream(true).start()
            val hit = proc.inputStream.bufferedReader().readText().trim().lineSequence().firstOrNull()
            proc.waitFor()
            hit?.takeIf { it.endsWith(".so") }.also { log("native preload lookup: ${it ?: "none"}") }
        } catch (e: Exception) {
            log("native preload lookup failed: ${e.message}")
            null
        }
    }
}
