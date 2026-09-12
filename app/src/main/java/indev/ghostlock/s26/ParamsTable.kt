package indev.ghostlock.s26

/**
 * Kotlin mirror of exploit/src/params_table.c + the matching logic in
 * exploit/src/params.c.
 *
 * Covers the WHOLE Galaxy S26 series, not one device: one APK, three kernel
 * lines (cn / intl / exynos), 17 tested builds across 5 device codenames:
 *  - m1q  SM-S942x  S26 Snapdragon
 *  - m2q  SM-S947x  S26+ Snapdragon
 *  - m3q  SM-S948x  S26 Ultra (Snapdragon CN + intl)
 *  - m1s  SM-S942B  S26 Exynos
 *  - m2s  SM-S947B  S26+ Exynos
 *
 * The native table inside preload.so is authoritative at runtime; this copy
 * exists so the UI can fail-closed early with a clear message instead of
 * burning a boot-claim on a build the exploit will refuse anyway.
 *
 * Keep in sync with exploit/src/params_table.c when upstream adds builds.
 */
object ParamsTable {

    data class DeviceEntry(val buildId: String, val device: String, val line: String)

    // Must match `kernel_lines[].line_id` in params_table.c
    val lines = setOf("cn", "intl", "exynos")

    /** Human-readable coverage per device codename. */
    data class FamilyMember(
        val codename: String,
        val marketing: String,
        val soc: String,
        val line: String,
    )

    val family: List<FamilyMember> = listOf(
        FamilyMember("m1q", "Galaxy S26 (SM-S942x)", "Snapdragon", "cn or intl by CSC"),
        FamilyMember("m2q", "Galaxy S26+ (SM-S947x)", "Snapdragon", "cn or intl by CSC"),
        FamilyMember("m3q", "Galaxy S26 Ultra (SM-S948x)", "Snapdragon", "cn or intl by CSC"),
        FamilyMember("m1s", "Galaxy S26 (SM-S942B)", "Exynos", "exynos"),
        FamilyMember("m2s", "Galaxy S26+ (SM-S947B)", "Exynos", "exynos"),
    )

    val deviceMap: List<DeviceEntry> = listOf(
        DeviceEntry("S9420ZCS4AZG1", "m1q", "cn"),
        DeviceEntry("S9470ZCS4AZG1", "m2q", "cn"),
        DeviceEntry("S9480ZCS3AZF1", "m3q", "cn"),
        DeviceEntry("S9480ZCS4AZG1", "m3q", "cn"),
        DeviceEntry("S942BXXS4AZG5", "m1s", "exynos"),
        DeviceEntry("S947BXXS3AZF1", "m2s", "exynos"),
        DeviceEntry("S947BXXS4AZG5", "m2s", "exynos"),
        DeviceEntry("S942QOPU1AZDE", "m1q", "intl"),
        DeviceEntry("S942U1UES4AZG3", "m1q", "intl"),
        DeviceEntry("S942USQS4AZG3", "m1q", "intl"),
        DeviceEntry("S947USQS4AZG3", "m2q", "intl"),
        DeviceEntry("S9480ZHS4AZG1", "m3q", "intl"),
        DeviceEntry("S948BXXS4AZG5", "m3q", "intl"),
        DeviceEntry("S948BXXS4AZG6", "m3q", "intl"),
        DeviceEntry("S948NKSS4AZG3", "m3q", "intl"),
        DeviceEntry("S948U1UES2AZE1", "m3q", "intl"),
        DeviceEntry("S948USQS4AZG3", "m3q", "intl"),
    )

    sealed interface Match {
        data class Exact(val entry: DeviceEntry) : Match
        /**
         * Same model + same 3-char CSC, unknown OTA revision: native reuses
         * that line (OTA reuse). Works when Samsung ships a new revision on
         * an already-covered model+CSC.
         */
        data class ModelCscFallback(val entry: DeviceEntry, val fromBuild: String) : Match
        /**
         * Same device codename only, unknown model/CSC: native assumes the
         * latest entry's line, flagged MARKET UNVERIFIED. May work, may refuse.
         */
        data class SameDeviceFallback(val entry: DeviceEntry) : Match
        object Unsupported : Match
    }

    /**
     * Full port of params_resolve() in exploit/src/params.c:
     *  1. exact incremental (+ device check)
     *  2. same model + same CSC (first 3 chars after the model code), newest
     *  3. same device codename, newest
     *  4. fail-closed
     *
     * @param incremental ro.build.version.incremental (the build id, e.g. S948BXXS4AZG5)
     * @param device ro.product.device (e.g. m3q)
     * @param model ro.product.model (e.g. SM-S948B)
     */
    fun match(incremental: String, device: String, model: String = ""): Match {
        // 1. Exact.
        deviceMap.firstOrNull {
            it.buildId == incremental && (it.device.isEmpty() || device.isEmpty() || it.device == device)
        }?.let { return Match.Exact(it) }

        val modelCode = model.removePrefix("SM-").removePrefix("SM")
        val modelOk = modelCode.length >= 5 &&
            incremental.length >= modelCode.length + 3 &&
            incremental.startsWith(modelCode)

        // 2. Same model + CSC fallback (OTA reuse).
        if (modelOk) {
            var hit: DeviceEntry? = null
            for (e in deviceMap) {
                if (e.device.isNotEmpty() && device.isNotEmpty() && e.device != device) continue
                val eb = e.buildId
                if (eb.length < modelCode.length + 3) continue
                if (!eb.startsWith(modelCode)) continue
                if (eb.substring(modelCode.length, modelCode.length + 3) !=
                    incremental.substring(modelCode.length, modelCode.length + 3)
                ) continue
                if (hit == null || eb > hit.buildId) hit = e
            }
            if (hit != null) return Match.ModelCscFallback(hit, hit.buildId)
        }

        // 3. Same device fallback (latest entry, unverified).
        if (device.isNotBlank()) {
            deviceMap.filter { it.device == device }.maxByOrNull { it.buildId }
                ?.let { return Match.SameDeviceFallback(it) }
        }

        // 4. Fail-closed: unknown model entirely.
        return Match.Unsupported
    }

    /** All builds for one device codename (for the device card / README). */
    fun buildsFor(device: String): List<DeviceEntry> =
        deviceMap.filter { it.device == device }.sortedBy { it.buildId }
}
