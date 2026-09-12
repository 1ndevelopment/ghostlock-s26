package indev.ghostlock.s26

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Installs the bundled KernelSU Manager APK through the system
 * PackageInstaller, so the user gets the standard confirmation dialog
 * ("asked to install") instead of a silent root-side `pm install`.
 */
object KernelSuInstaller {

    const val PACKAGE = "me.weishu.kernelsu"
    const val ASSET = "kernelsu-manager.apk"

    fun isInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(PACKAGE, 0)
        true
    } catch (_: Exception) {
        false
    }

    /** Android 8+ gate: the user must allow "install unknown apps" for us. */
    fun canRequestInstalls(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    /**
     * Streams the bundled APK into a PackageInstaller session and commits it.
     * Returns true when the session was accepted; the final outcome arrives
     * asynchronously in [InstallReceiver].
     */
    suspend fun install(context: Context): Boolean = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(PACKAGE)
        try {
            val sessionId = pm.packageInstaller.createSession(params)
            val session = pm.packageInstaller.openSession(sessionId)
            try {
                val out = session.openWrite("kernelsu-manager.apk", 0, -1)
                context.assets.open(ASSET).use { it.copyTo(out) }
                session.fsync(out)
                out.close()

                val intent = Intent(context, InstallReceiver::class.java)
                val pi = PendingIntent.getBroadcast(
                    context, sessionId, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                session.commit(pi.intentSender)
                true
            } catch (e: Exception) {
                try {
                    session.abandon()
                } catch (_: Exception) {
                }
                false
            } finally {
                try {
                    session.close()
                } catch (_: Exception) {
                }
            }
        } catch (e: Exception) {
            false
        }
    }
}