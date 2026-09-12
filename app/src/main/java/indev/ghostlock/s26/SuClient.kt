package indev.ghostlock.s26

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.system.OsConstants
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Root command execution with two channels:
 *
 *  1. temp-daemon — su_daemon at /data/local/tmp/temp_su.sock ('C' mode).
 *     Alive between the UMH exec and the KernelSU late-load.
 *  2. su — KernelSU (or other) su binary. This is the steady state: on a
 *     FULL success su_daemon unlinks its socket and exits by design
 *     (see serve_one() 'K' mode), handing over to KernelSU. Knocking on the
 *     temp socket after that will always fail — that means rooted, not broken.
 */
object SuClient {

    const val SOCK_PATH = "/data/local/tmp/temp_su.sock"

    /** su binaries to try, in order. Plain "su" first (PATH). */
    private val SU_CANDIDATES = listOf(
        "su",
        "/system/bin/su",
        "/system/xbin/su",
        "/data/adb/ksu/bin/su",
        "/data/adb/ap/bin/su",
        "/sbin/su",
        "/su/bin/su",
    )

    data class RootResult(val output: String, val via: String)

    /** True root check: `id` must report uid=0 through some channel. */
    suspend fun isAlive(preferShizuku: Boolean = true): Boolean {
        return try {
            execRoot("id", preferShizuku).output.contains("uid=0")
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Run [cmd] as root. Tries the temp daemon socket first, then each su
     * candidate. Returns the output plus which channel worked. Throws with a
     * message summarizing every channel's failure if none worked.
     */
    suspend fun execRoot(
        cmd: String,
        preferShizuku: Boolean = true,
        timeoutMs: Int = 15_000,
    ): RootResult {
        val failures = mutableListOf<String>()

        try {
            return RootResult(execTemp(cmd, timeoutMs), "temp-daemon")
        } catch (e: Exception) {
            failures += "temp-daemon: ${e.message}"
        }

        // Dynamic discovery first: whatever `su` the shell resolves wins over
        // the static list (covers Magisk/APatch/KernelSU install locations).
        // Also probe for toybox `timeout` — if it's missing, skip the guard
        // prefix entirely (a missing `timeout` would otherwise fail EVERY
        // candidate with 127 and look like "no root").
        val discovery: ShellRunner.Result = try {
            ShellRunner.sh("command -v su; command -v timeout", preferShizuku = preferShizuku)
        } catch (e: Exception) {
            failures += "shell: ${e.message}"
            throw IllegalStateException(failures.joinToString(" | "))
        }
        val found = discovery.output.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.contains("not found") }
            .toList()
        val dynamicSu = found.firstOrNull { !it.endsWith("timeout") }
        val hasTimeout = found.any { it.endsWith("timeout") }
        val prefix = if (hasTimeout) "timeout 12 " else ""

        val candidates = (listOfNotNull(dynamicSu) + SU_CANDIDATES).distinct()
        for (su in candidates) {
            // toybox `timeout` guards against su blocking on a prompt.
            val script = "${prefix}$su -c '${cmd.replace("'", "'\\''")}' 2>&1"
            val r = try {
                ShellRunner.sh(script, preferShizuku = preferShizuku)
            } catch (e: Exception) {
                failures += "$su: shell error ${e.message}"
                continue
            }
            // exit 0 = success, even with empty output (e.g. `touch`).
            if (r.exitCode == 0) {
                return RootResult(r.output, "su ($su)")
            }
            val why = r.output.trim().lineSequence().firstOrNull().orEmpty()
            failures += "$su: exit=${r.exitCode}${if (why.isNotBlank()) " $why" else ""}"
        }

        throw IllegalStateException(failures.joinToString(" | "))
    }

    /** One-shot 'C'-mode command through the temp daemon socket. */
    fun execTemp(cmd: String, timeoutMs: Int = 15_000): String {
        val addrFile = LocalSocketAddress(SOCK_PATH, LocalSocketAddress.Namespace.FILESYSTEM)
        var lastErr: Exception? = null
        // Filesystem namespace first (matches ksu_proto.h), abstract as fallback.
        val attempts = listOf(
            addrFile,
            LocalSocketAddress(SOCK_PATH, LocalSocketAddress.Namespace.ABSTRACT),
        )
        for (addr in attempts) {
            try {
                return execVia(addr, cmd, timeoutMs)
            } catch (e: Exception) {
                lastErr = e
            }
        }
        throw lastErr ?: IllegalStateException("su daemon unreachable")
    }

    private fun execVia(addr: LocalSocketAddress, cmd: String, timeoutMs: Int): String {
        val sock = LocalSocket()
        try {
            sock.connect(addr)
            sock.soTimeout = timeoutMs
            val out = sock.outputStream
            val bytes = cmd.toByteArray()
            out.write('C'.code)
            val len = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(bytes.size).array()
            out.write(len)
            out.write(bytes)
            out.flush()
            // Half-close so the daemon sees EOF on its read side.
            try {
                android.system.Os.shutdown(sock.fileDescriptor, OsConstants.SHUT_WR)
            } catch (_: Exception) {
                // Older devices: fall back to full close semantics below.
            }
            val inp = sock.inputStream
            val buf = ByteArray(8192)
            val sb = StringBuilder()
            while (true) {
                val n = try {
                    inp.read(buf)
                } catch (e: java.net.SocketTimeoutException) {
                    break
                }
                if (n <= 0) break
                sb.append(String(buf, 0, n))
                if (sb.length > 256 * 1024) break // sanity cap
            }
            if (sb.isEmpty()) throw EOFException("empty reply from su daemon")
            return sb.toString()
        } finally {
            try { sock.close() } catch (_: Exception) { }
        }
    }
}
