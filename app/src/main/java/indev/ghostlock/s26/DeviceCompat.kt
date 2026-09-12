package indev.ghostlock.s26

import android.os.Build

/**
 * Reads the same properties exploit/src/params.c uses:
 * ro.build.version.incremental, ro.product.device, ro.product.model,
 * ro.build.fingerprint.
 *
 * Build.VERSION.INCREMENTAL / Build.DEVICE / Build.MODEL / Build.FINGERPRINT
 * are the SDK projections of those properties, so no reflection is needed.
 */
object DeviceCompat {

    data class Identity(
        val incremental: String,
        val device: String,
        val model: String,
        val fingerprint: String,
        val kernel: String,
        val abi: String,
    )

    fun read(): Identity = Identity(
        incremental = Build.VERSION.INCREMENTAL ?: "",
        device = Build.DEVICE ?: "",
        model = Build.MODEL ?: "",
        fingerprint = Build.FINGERPRINT ?: "",
        kernel = System.getProperty("os.version").orEmpty(),
        abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
    )

    fun describe(i: Identity): String = buildString {
        appendLine("model=${i.model} device=${i.device}")
        appendLine("incremental=${i.incremental}")
        appendLine("fingerprint=${i.fingerprint}")
        append("abi=${i.abi} kernel=${i.kernel}")
    }

    /**
     * Series-wide verdict. Returns (text, runnable).
     * Mirrors params.c: exact > model+CSC OTA reuse > same-device guess > fail-closed.
     */
    fun compatText(i: Identity): Pair<String, Boolean> {
        if (!i.abi.startsWith("arm64")) {
            return "UNSUPPORTED: need arm64-v8a, this device reports ${i.abi}" to false
        }
        return when (val m = ParamsTable.match(i.incremental, i.device, i.model)) {
            is ParamsTable.Match.Exact ->
                "SUPPORTED - Galaxy S26 series\n" +
                    "device=${m.entry.device} build=${m.entry.buildId} line=${m.entry.line} (exact match)" to true
            is ParamsTable.Match.ModelCscFallback ->
                "LIKELY (OTA reuse) - Galaxy S26 series\n" +
                    "build ${i.incremental} not listed; same model+CSC as ${m.fromBuild} → line=${m.entry.line}.\n" +
                    "Native layer decides; may still refuse (fail-closed)." to true
            is ParamsTable.Match.SameDeviceFallback ->
                "GUESS (unverified) - Galaxy S26 series\n" +
                    "device=${i.device} incremental=${i.incremental} unknown; " +
                    "would assume line=${m.entry.line} from latest entry ${m.entry.buildId}.\n" +
                    "Native layer decides; may refuse (fail-closed)." to true
            ParamsTable.Match.Unsupported ->
                "UNSUPPORTED - not a known Galaxy S26 build\n" +
                    "device=${i.device} incremental=${i.incremental} model=${i.model}.\n" +
                    "Exploit will exit 2 (fail-closed). This firmware needs a port - see PORTING.upstream.md." to false
        }
    }
}
