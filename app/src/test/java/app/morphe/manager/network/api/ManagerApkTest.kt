/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.network.api

import kotlin.test.*

/**
 * An update to the APK of an ABI the device does not run fails to install, while one to the
 * universal APK only takes longer to download. Picking errs toward the universal APK.
 */
class ManagerApkTest {
    private val universal = "https://github.com/MorpheApp/morphe-manager/releases/download/v2.6.0/morphe-manager-2.6.0.apk"
    private val abiUrls = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86").associateWith {
        "https://github.com/MorpheApp/morphe-manager/releases/download/v2.6.0/morphe-manager-$it-2.6.0.apk"
    }

    @Test
    fun `a 64-bit ARM device gets its own APK`() {
        assertEquals(abiUrls["arm64-v8a"], ManagerApk.downloadUrl(universal, abiUrls, listOf("arm64-v8a", "armeabi-v7a", "armeabi")))
    }

    @Test
    fun `a 32-bit only ARM device gets the 32-bit APK`() {
        assertEquals(abiUrls["armeabi-v7a"], ManagerApk.downloadUrl(universal, abiUrls, listOf("armeabi-v7a", "armeabi")))
    }

    @Test
    fun `an x86 device that translates ARM keeps to its own ABI`() {
        assertEquals(abiUrls["x86_64"], ManagerApk.downloadUrl(universal, abiUrls, listOf("x86_64", "x86", "arm64-v8a")))
    }

    @Test
    fun `a release without APKs per ABI leaves the universal APK`() {
        assertEquals(universal, ManagerApk.downloadUrl(universal, emptyMap(), listOf("arm64-v8a")))
    }

    @Test
    fun `a device none of whose ABIs has an APK gets the universal one`() {
        assertEquals(universal, ManagerApk.downloadUrl(universal, abiUrls, listOf("riscv64")))
        assertEquals(universal, ManagerApk.downloadUrl(universal, abiUrls, emptyList()))
    }

    private val releaseApks = listOf(
        "morphe-manager-arm64-v8a-2.6.0-dev.3.apk",
        "morphe-manager-armeabi-v7a-2.6.0-dev.3.apk",
        "morphe-manager-2.6.0-dev.3.apk",
        "morphe-manager-x86-2.6.0-dev.3.apk",
        "morphe-manager-x86_64-2.6.0-dev.3.apk"
    )

    @Test
    fun `the APK of the device is picked from a release whatever the order it lists them in`() {
        assertEquals("morphe-manager-arm64-v8a-2.6.0-dev.3.apk", ManagerApk.pick(releaseApks, listOf("arm64-v8a", "armeabi-v7a")))
        assertEquals("morphe-manager-x86_64-2.6.0-dev.3.apk", ManagerApk.pick(releaseApks.reversed(), listOf("x86_64", "x86")))
        assertEquals("morphe-manager-x86-2.6.0-dev.3.apk", ManagerApk.pick(releaseApks, listOf("x86")))
    }

    @Test
    fun `the universal APK is picked where the device has none of its own`() {
        assertEquals("morphe-manager-2.6.0-dev.3.apk", ManagerApk.pick(releaseApks, listOf("riscv64")))
        assertEquals("morphe-manager-2.5.0.apk", ManagerApk.pick(listOf("morphe-manager-2.5.0.apk"), listOf("arm64-v8a")))
    }

    @Test
    fun `a release of only APKs for other ABIs offers nothing`() {
        assertNull(ManagerApk.pick(listOf("morphe-manager-x86_64-2.6.0.apk"), listOf("arm64-v8a")))
        assertNull(ManagerApk.pick(emptyList(), listOf("arm64-v8a")))
    }
}
