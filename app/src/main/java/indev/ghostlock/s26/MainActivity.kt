package indev.ghostlock.s26

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import indev.ghostlock.s26.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private var running = false
    /** True while some channel reports uid=0. Drives the Root button state. */
    private var rooted = false
    /** Auto-follow the log only while the user is already at its bottom. */
    private var followLog = true
    private val tsFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val shizukuBinderListener = Shizuku.OnBinderReceivedListener {
        updateShizukuStatus()
        // Seamless: the moment the Shizuku server comes up, ask for the shell
        // token so the Root flow never has to interrupt.
        if (!ShellRunner.shizukuPermission()) {
            ShellRunner.requestShizukuPermission(1001)
        }
    }
    private val shizukuPermListener =
        Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            log(
                if (grantResult == PackageManager.PERMISSION_GRANTED)
                    "Shizuku permission granted."
                else "Shizuku permission denied - will try the in-app shell instead."
            )
            updateShizukuStatus()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        // Edge-to-edge: draw behind the status/nav bars and pad the page by
        // the system-bar insets (incl. cutout) so nothing hides underneath.
        ViewCompat.setOnApplyWindowInsetsListener(b.outerScroll) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }

        val ident = DeviceCompat.read()
        val (compatText, _) = DeviceCompat.compatText(ident)
        b.compatInfo.text = compatText.lineSequence().first()
        b.compatInfo.setTextColor(
            getColor(
                when {
                    compatText.startsWith("SUPPORTED") -> android.R.color.holo_green_light
                    compatText.startsWith("LIKELY") || compatText.startsWith("GUESS") -> android.R.color.holo_orange_light
                    else -> android.R.color.holo_red_light
                }
            )
        )
        // Full identity + verdict live in the console so the header stays compact.
        DeviceCompat.describe(ident).lineSequence().forEach { log(it) }
        log(compatText.replace("\n", " · "))

        b.btnRoot.setOnClickListener { guard { rootPipeline() } }
        b.btnExec.setOnClickListener { guard { execAsRoot() } }
        b.btnClearLog.setOnClickListener { b.logView.text = "" }
        b.btnCopyLog.setOnClickListener {
            val text = b.logView.text.toString()
            if (text.isBlank()) {
                toast("Nothing to copy yet")
                return@setOnClickListener
            }
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("GhostLock log", text))
            toast("Log copied (${text.length} chars)")
        }
        b.btnReset.setOnClickListener {
            guard {
                val r = ShellRunner.sh("rm -f ${ShellRunner.BOOT_LOG} && echo removed || echo 'rm failed'")
                log(r.output.trim())
                toast("Attempt flag cleared - reboot is still the safer path")
            }
        }

        updateShizukuStatus()
        // Seamless Shizuku: request the shell token once at launch (it lasts
        // until the Shizuku server restarts), so the Root flow never asks.
        lifecycleScope.launch {
            if (ShellRunner.isShizukuAvailable() && !ShellRunner.shizukuPermission()) {
                log("Shizuku is running - requesting permission (one-time per session).")
                val granted = ShellRunner.requestShizukuPermissionAndWait()
                if (granted != PackageManager.PERMISSION_GRANTED) {
                    log("Shizuku permission denied - the in-app shell will be used (lower odds).")
                }
                updateShizukuStatus()
            }
        }
        // The console scrolls on its own: only auto-follow while the user is
        // already at the bottom - never yank them away from reading.
        b.logScroll.setOnScrollChangeListener { v, _, scrollY, _, _ ->
            val child = (v as android.widget.ScrollView).getChildAt(0)
            followLog = child == null || scrollY + v.height >= child.bottom - 48
        }
        log("Tap Root my S26. It checks the device, stages the payload, runs the exploit (retries - the race is probabilistic), and verifies root.")
        // Probe once at launch; onResume re-probes (temp root is gone after reboot).
        lifecycleScope.launch { refreshRootState() }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch { refreshRootState() }
    }

    override fun onStart() {
        super.onStart()
        try {
            Shizuku.addBinderReceivedListenerSticky(shizukuBinderListener)
            Shizuku.addRequestPermissionResultListener(shizukuPermListener)
        } catch (_: Exception) { }
    }

    override fun onStop() {
        try {
            Shizuku.removeBinderReceivedListener(shizukuBinderListener)
            Shizuku.removeRequestPermissionResultListener(shizukuPermListener)
        } catch (_: Exception) { }
        super.onStop()
    }

    private inline fun guard(crossinline block: suspend () -> Unit) {
        if (running) {
            toast("Busy - wait for the current task")
            return
        }
        running = true
        setBusy(true)
        lifecycleScope.launch {
            try {
                block()
            } catch (e: Exception) {
                log("ERROR: ${e.message}")
            } finally {
                running = false
                setBusy(false)
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        b.btnRoot.isEnabled = !busy && !rooted
        b.btnExec.isEnabled = !busy
        b.btnReset.isEnabled = !busy
        if (busy) b.exploitStatus.text = "Working…"
    }

    /** Probe uid=0 through any channel and reflect it on the Root button. */
    private suspend fun refreshRootState() {
        val r = withContext(Dispatchers.IO) {
            try {
                SuClient.isAlive()
            } catch (_: Exception) {
                false
            }
        }
        rooted = r
        b.btnRoot.isEnabled = !r && !running
        b.btnRoot.text = if (r) "Already rooted" else "Root my S26"
        if (r) status("Rooted (uid=0).", android.R.color.holo_green_light)
    }

    private fun markRooted(via: String) {
        rooted = true
        b.btnRoot.isEnabled = false
        b.btnRoot.text = "Already rooted"
        status("ROOTED via $via (uid=0).", android.R.color.holo_green_light)
    }

    /** The whole flow behind the single button. */
    private suspend fun rootPipeline() {
        // 1. Series check (fail-closed, mirrors the native table).
        val ident = DeviceCompat.read()
        val (compatText, runnable) = DeviceCompat.compatText(ident)
        log("Device check: ${compatText.lineSequence().first()}")
        if (!runnable) {
            status("Unsupported device - stopping.", android.R.color.holo_red_light)
            log("This build is not in the exploit's table, so it would refuse (exit 2). See PORTING notes to add it.")
            return
        }

        // 2. Shizuku (uid 2000 shell). Permission is auto-requested at launch
        //    and again here if needed; the flow waits for the answer instead
        //    of bailing out. If the server is down, open the manager so the
        //    user can start it, then fall back to the in-app shell.
        var useShizuku = false
        if (ShellRunner.isShizukuAvailable()) {
            if (!ShellRunner.shizukuPermission()) {
                log("Shizuku is running but permission isn't granted - requesting it now.")
                val granted = ShellRunner.requestShizukuPermissionAndWait()
                if (granted != PackageManager.PERMISSION_GRANTED) {
                    log("Shizuku permission denied - continuing with the in-app shell (lower odds).")
                }
            }
            useShizuku = ShellRunner.shizukuPermission()
        } else {
            log("Shizuku not running - opening the Shizuku manager so you can start it (wireless debugging).")
            launchShizukuManager()
        }
        updateShizukuStatus()

        // 3. Stage payload to /data/local/tmp.
        status("Staging payload…", android.R.color.holo_orange_light)
        log("Staging payload → /data/local/tmp")
        val staged: ShellRunner.Result = try {
            ShellRunner.stage(this@MainActivity, preferShizuku = useShizuku, onLine = ::logLine)
        } catch (e: IllegalStateException) {
            log("STAGE: ${e.message}")
            status("Staging failed.", android.R.color.holo_red_light)
            return
        }
        if (staged.exitCode != 0) {
            log(staged.output.trim())
            log("Staging failed. If you already pushed the files via adb (README flow), they may still be in place - otherwise fix staging first.")
            status("Staging failed.", android.R.color.holo_red_light)
            return
        }
        log("Stage OK.")

        // 4. Run (up to 5 attempts; the race is probabilistic).
        val force = b.switchForce.isChecked
        if (force) log("BOOT_FORCE=1 set - same-boot re-run may panic. Reboot is safer.")
        status("Running exploit (up to 5 attempts)…", android.R.color.holo_orange_light)
        log("Running exploit (up to 5 attempts)…")
        val outcome = GhostlockManager.runRetries(
            times = 5,
            bootForce = force,
            debug = false,
            preferShizuku = useShizuku,
            onLine = ::logLine,
            onAttempt = { i, o ->
                val txt = when (o) {
                    is GhostlockManager.AttemptOutcome.Success -> "Attempt $i: SUCCESS"
                    is GhostlockManager.AttemptOutcome.Failed -> "Attempt $i: exit ${o.exitCode}"
                }
                lifecycleScope.launch(Dispatchers.Main) { b.exploitStatus.text = txt }
            }
        )
        log("Verifying root…")
        appendBootLog(useShizuku)
        when (outcome) {
            is GhostlockManager.AttemptOutcome.Success -> {
                markRooted(outcome.via)
                log("Temporary root granted (${outcome.via}). It vanishes on reboot.")
            }
            is GhostlockManager.AttemptOutcome.Failed -> {
                status("Exit ${outcome.exitCode}: ${shortAdvice(outcome.exitCode)}", android.R.color.holo_orange_light)
                log("Advice: ${outcome.advice}")
            }
        }
    }

    private suspend fun appendBootLog(useShizuku: Boolean) {
        val r = ShellRunner.sh(
            "cat ${ShellRunner.BOOT_LOG} 2>&1 || echo '(no boot log yet)'",
            preferShizuku = useShizuku,
        )
        log("boot log: " + r.output.trim().replace("\n", " | "))
    }

    private suspend fun execAsRoot() {
        val cmd = b.cmdInput.text?.toString().orEmpty()
        if (cmd.isBlank()) {
            toast("Enter a command")
            return
        }
        log("# $cmd")
        try {
            val res = SuClient.execRoot(cmd)
            log("[via ${res.via}]\n" + res.output.trimEnd().ifBlank { "(no output)" })
            if ("uid=0" in res.output) markRooted(res.via)
        } catch (e: Exception) {
            log("No root channel worked: ${e.message}")
            log("If KernelSU Manager shows this device rooted, open it and allowlist GhostLock, then retry.")
        }
    }

    private fun status(txt: String, colorRes: Int) {
        b.exploitStatus.text = txt
        b.exploitStatus.setTextColor(getColor(colorRes))
    }

    /** Open the Shizuku manager so the user can start the server (wireless
     *  debugging) - the app itself cannot start it (needs adb/root). */
    private fun launchShizukuManager() {
        try {
            val intent = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
            if (intent != null) {
                startActivity(intent)
            } else {
                log("Shizuku manager not installed - get it from https://shizuku.rikka.app/")
            }
        } catch (e: Exception) {
            log("Could not open Shizuku manager: ${e.message}")
        }
    }

    private fun shortAdvice(code: Int): String = when (code) {
        0 -> "daemon silent - wait, then run a command"
        2 -> "unsupported build"
        4 -> "already ran this boot - reboot"
        3 -> "carrier/root failed - reboot, retry"
        else -> "race missed - tap Root again"
    }

    private fun updateShizukuStatus() {
        // Shizuku listeners fire on a binder thread - never touch views directly.
        runOnUiThread {
            b.shizukuStatus.text = when {
                !ShellRunner.isShizukuAvailable() -> "Shizuku: not running"
                !ShellRunner.shizukuPermission() -> "Shizuku: running, permission not granted"
                else -> "Shizuku: connected (uid 2000 path)"
            }
        }
    }

    private fun log(s: String) {
        runOnUiThread { logLine(s) }
    }

    private fun logLine(s: String) {
        runOnUiThread {
            val sb = SpannableStringBuilder()
            val ts = tsFormat.format(Date())
            sb.append(ts)
            sb.setSpan(
                ForegroundColorSpan(getColor(R.color.muted)),
                0, ts.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            sb.append("  ")
            val lineStart = sb.length
            sb.append(s)
            val style = styleFor(s)
            sb.setSpan(
                ForegroundColorSpan(getColor(style.colorRes)),
                lineStart, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            if (style.bold) {
                sb.setSpan(StyleSpan(Typeface.BOLD), lineStart, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            b.logView.append(sb)
            b.logView.append("\n")
            if (followLog) {
                b.logScroll.post { b.logScroll.fullScroll(android.view.View.FOCUS_DOWN) }
            }
        }
    }

    private data class LogStyle(val colorRes: Int, val bold: Boolean = false)

    /** Map a log line to its terminal-ish color. Order matters (first match wins). */
    private fun styleFor(line: String): LogStyle {
        val t = line.lowercase()
        return when {
            t.contains("failed") || t.contains("error") || t.contains("unsupported") ||
                t.contains("no root channel") || t.contains("unavailable") ||
                t.contains("no shell channel") || t.contains("cannot") -> LogStyle(R.color.danger)
            t.contains("success") || t.contains("granted") || t.contains("rooted") ||
                t.contains("stage ok") || t.contains("uid=0") || t.contains("exit 0") ||
                t.contains("removed") -> LogStyle(R.color.accent)
            t.startsWith("#") -> LogStyle(R.color.log_cmd)
            t.contains("exit ") || t.contains("not running") || t.contains("boot_force") ||
                t.contains("may crash") || t.contains("risky") || t.contains("denied") ||
                t.contains("missing") || t.contains("warning") || t.contains("lower odds") ->
                LogStyle(R.color.warn)
            (t.contains("attempt") && t.contains("/")) || t.startsWith("staging payload") ||
                t.startsWith("running exploit") || t.startsWith("device check") ||
                t.startsWith("verifying") -> LogStyle(R.color.accent, bold = true)
            t.startsWith("boot log") || t.startsWith("advice") -> LogStyle(R.color.muted)
            else -> LogStyle(R.color.log_normal)
        }
    }

    private fun toast(s: String) {
        runOnUiThread { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }
    }
}
