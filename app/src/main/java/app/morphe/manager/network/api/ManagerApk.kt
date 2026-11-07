/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.network.api

/**
 * Releases ship a universal APK and one per ABI, `morphe-manager-<abi>-<version>.apk`, which
 * carries the native libraries and embedded JVM of its ABI alone and so takes far less to
 * download. A device takes the APK of the first of its ABIs a release ships one for, the ABI
 * Android would run the universal APK as, and the universal APK where a release ships none.
 */
internal object ManagerApk {
    /** The URL of the APK for a device with [supportedAbis], out of a release JSON's. */
    fun downloadUrl(
        universalUrl: String,
        abiUrls: Map<String, String>,
        supportedAbis: List<String>
    ): String = supportedAbis.firstNotNullOfOrNull { abiUrls[it] } ?: universalUrl

    /**
     * The APK for a device with [supportedAbis] out of [apkNames], the names of the APKs a release
     * on GitHub lists. Null where it lists none, or only APKs for ABIs the device does not run.
     */
    fun pick(apkNames: List<String>, supportedAbis: List<String>): String? {
        val abiApks = apkNames.mapNotNull { name -> abiOf(name)?.let { it to name } }.toMap()
        return supportedAbis.firstNotNullOfOrNull { abiApks[it] }
            ?: apkNames.firstOrNull { abiOf(it) == null }
    }

    /** The ABI an APK is for, null for the universal APK. */
    private fun abiOf(apkName: String): String? =
        ABI_APK.matchEntire(apkName)?.groupValues?.get(1)?.takeIf { it in ABIS }

    private val ABI_APK = Regex("morphe-manager-(.+?)-\\d.*\\.apk", RegexOption.IGNORE_CASE)

    private val ABIS = setOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
}
