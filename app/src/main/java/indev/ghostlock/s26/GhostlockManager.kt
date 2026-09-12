package indev.ghostlock.s26

import kotlinx.coroutines.delay

/**
 * Orchestrates stage -> run -> verify, interpreting the native exit codes
 * (see run_exploit / preload.c):
 *  0  success (su socket up, ksud late-load OK)
 *  1  race failed / ksud verify failed - retry after REBOOT is pointless for
 *     the race itself; just retry (probabilistic), it usually takes several.
 *  2  unsupported build (fail-closed) - do NOT retry, port needed
 *  3  carrier/root failed - reboot keeper required, retry risky
 *  4  boot-claim reject: already ran this boot - reboot before retry
 */
object GhostlockManager {

    const val BOOT_LOG = "/data/local/tmp/ghostlock-boot.log"

    sealed interface AttemptOutcome {
        data class Success(val output: String, val via: String) : AttemptOutcome
        data class Failed(val exitCode: Int, val output: String, val advice: String) : AttemptOutcome
    }

    fun adviceFor(exitCode: Int): String = when (exitCode) {
        0 -> "Success. su daemon should be listening now - press 'Check root'."
        2 -> "Unsupported build (fail-closed). Do not force; this firmware needs a port (see PORTING.upstream.md)."
        4 -> "Boot-claim reject: an attempt already ran this boot. REBOOT, then try again. BOOT_FORCE=1 overrides but may panic the device."
        3 -> "Carrier/root stage failed. Upstream notes a reboot keeper may be required; reboot before the next try."
        else -> "Race missed (probabilistic) - just retry. Several attempts are normal."
    }

    suspend fun runOnce(
        bootForce: Boolean,
        debug: Boolean,
        preferShizuku: Boolean,
        onLine: (String) -> Unit,
    ): AttemptOutcome {
        val r = ShellRunner.exploitAttempt(bootForce, debug, preferShizuku, onLine)
        // Give the daemon a beat to bind before the caller probes for root.
        if (r.exitCode == 0) delay(800)
        // NOTE: on a full success the temp daemon already unlinked its socket
        // and handed over to KernelSU, so verify through ANY channel (temp
        // socket first, then su) - not the socket alone.
        val root = try {
            SuClient.execRoot("id", preferShizuku)
        } catch (_: Exception) {
            null
        }
        val rooted = root != null && root.output.contains("uid=0")
        return if (r.exitCode == 0 && rooted) {
            AttemptOutcome.Success(r.output, root!!.via)
        } else if (r.exitCode == 0) {
            AttemptOutcome.Failed(
                0, r.output,
                "Exit 0 but no root channel answered (temp socket gone AND su failed). " +
                    "If KernelSU Manager shows root, allowlist this app and retry the command row. " +
                    "Otherwise wait a few seconds and tap Root again."
            )
        } else {
            AttemptOutcome.Failed(r.exitCode, r.output, adviceFor(r.exitCode))
        }
    }

    suspend fun runRetries(
        times: Int,
        bootForce: Boolean,
        debug: Boolean,
        preferShizuku: Boolean,
        onLine: (String) -> Unit,
        onAttempt: (Int, AttemptOutcome) -> Unit,
    ): AttemptOutcome {
        var last: AttemptOutcome = AttemptOutcome.Failed(-1, "", "no attempts ran")
        for (i in 1..times) {
            onLine("-- attempt $i/$times --")
            val o = runOnce(bootForce, debug, preferShizuku, onLine)
            last = o
            onAttempt(i, o)
            if (o is AttemptOutcome.Success) return o
            // Exit 2/4 won't heal by hammering: stop early.
            val code = (o as AttemptOutcome.Failed).exitCode
            if (code == 2 || code == 4) return o
            if (i < times) delay(1500)
        }
        return last
    }
}
