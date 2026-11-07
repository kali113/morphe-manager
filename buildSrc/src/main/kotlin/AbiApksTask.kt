/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

import com.android.apksig.ApkSigner
import com.android.apksig.ApkSignerEngine
import com.android.apksig.ApkSignerEngine.InputJarEntryInstructions
import com.android.apksig.ApkSignerEngine.InputJarEntryInstructions.OutputPolicy
import com.android.apksig.ApkVerifier
import com.android.apksig.DefaultApkSignerEngine
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.zip.ZipFile

/**
 * Builds from the universal APK of a variant one APK per ABI it ships native libraries for, named
 * after the ABI. Each keeps the native libraries and the embedded JVM of its own ABI only, as the
 * runtimes of the other ABIs make up most of the universal APK, see [EmbeddedJvmAssetsTask].
 *
 * The APK of an ABI without a runtime of its own leaves the JVM assets out altogether, as the app
 * patches in its own process there. Every other entry is copied as it is, compressed data included,
 * and the APK is signed again with the key of the universal one, as an update must be.
 */
abstract class AbiApksTask : DefaultTask() {
    /** The directory the variant writes its APK to. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val apkDirectory: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val keyStore: RegularFileProperty

    /** The keystore type, the default type of the JDK when unset, which also reads JKS. */
    @get:Input
    @get:Optional
    abstract val keyStoreType: Property<String>

    @get:Internal
    abstract val keyStorePassword: Property<String>

    @get:Input
    abstract val keyAlias: Property<String>

    @get:Internal
    abstract val keyPassword: Property<String>

    @get:Input
    abstract val minSdk: Property<Int>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun build() {
        val universal = apkDirectory.get().asFile.listFiles { file -> file.extension == "apk" }
            ?.singleOrNull()
            ?: throw GradleException("Expected a single APK in ${apkDirectory.get().asFile}")

        val entries = ZipFile(universal).use { zip -> zip.entries().asSequence().map { it.name }.toList() }
        val abis = entries.mapNotNull { LIBRARY.matchEntire(it)?.groupValues?.get(1) }.distinct().sorted()

        val output = outputDirectory.get().asFile.apply {
            deleteRecursively()
            mkdirs()
        }

        // An APK for one ABI or none already is what the APK of that ABI would be
        if (abis.size < 2) {
            logger.lifecycle("${universal.name} ships native libraries for ${abis.size} ABI(s), there is nothing to split")
            return
        }

        val (key, certificates) = signingKey()

        abis.forEach { abi ->
            val apk = output.resolve("$abi.apk")
            val kept = keptEntries(abi, entries)
            sign(universal, apk, key, certificates) { name -> name !in kept }
            verify(apk)
            logger.lifecycle("${apk.name}: ${apk.length()} bytes, ${universal.length() - apk.length()} less than ${universal.name}")
        }
    }

    /** The entries of the universal APK the APK of [abi] keeps. */
    private fun keptEntries(abi: String, entries: List<String>): Set<String> {
        val hasRuntime = "$JVM_ASSETS${abi}$RUNTIME_SUFFIX" in entries
        return entries.filterTo(mutableSetOf()) { name ->
            when {
                name.startsWith(LIBRARIES) -> LIBRARY.matchEntire(name)?.groupValues?.get(1) == abi
                name.startsWith(JVM_ASSETS) -> hasRuntime && jvmAssetAbi(name).let { it == null || it == abi }
                else -> true
            }
        }
    }

    /** The ABI a runtime or image delta asset belongs to, null for an asset all ABIs share. */
    private fun jvmAssetAbi(name: String): String? {
        val file = name.removePrefix(JVM_ASSETS)
        return listOf(RUNTIME_SUFFIX, DELTA_SUFFIX).firstNotNullOfOrNull { suffix ->
            file.removeSuffix(suffix).takeIf { file.endsWith(suffix) }
        }
    }

    private fun signingKey(): Pair<PrivateKey, List<X509Certificate>> {
        val password = keyStorePassword.orNull?.toCharArray()
            ?: throw GradleException("The keystore password of the variant is not set")
        val store = KeyStore.getInstance(keyStoreType.orNull ?: KeyStore.getDefaultType()).apply {
            keyStore.get().asFile.inputStream().use { load(it, password) }
        }

        val alias = keyAlias.get()
        val key = store.getKey(alias, (keyPassword.orNull ?: String(password)).toCharArray()) as? PrivateKey
            ?: throw GradleException("The keystore holds no private key for $alias")
        val certificates = store.getCertificateChain(alias).map { it as X509Certificate }
        return key to certificates
    }

    // Schemes as the Android Gradle plugin signs the universal APK above API level 24: v2 alone
    private fun sign(
        input: File,
        output: File,
        key: PrivateKey,
        certificates: List<X509Certificate>,
        skip: (String) -> Boolean
    ) {
        val signer = DefaultApkSignerEngine.SignerConfig.Builder(SIGNER_NAME, key, certificates).build()
        val engine = DefaultApkSignerEngine.Builder(listOf(signer), minSdk.get())
            .setV1SigningEnabled(false)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(false)
            .build()

        SkippingSignerEngine(engine, skip).use {
            ApkSigner.Builder(it)
                .setInputApk(input)
                .setOutputApk(output)
                .build()
                .sign()
        }
    }

    private fun verify(apk: File) {
        val result = ApkVerifier.Builder(apk).setMinCheckedPlatformVersion(minSdk.get()).build().verify()
        if (!result.isVerified) {
            throw GradleException("${apk.name} does not verify: ${result.allErrors.joinToString()}")
        }
    }

    /** Leaves out the entries [skip] matches, the signing of what is left it hands to [engine]. */
    private class SkippingSignerEngine(
        private val engine: ApkSignerEngine,
        private val skip: (String) -> Boolean
    ) : ApkSignerEngine by engine {
        override fun inputJarEntry(entryName: String): InputJarEntryInstructions =
            if (skip(entryName)) InputJarEntryInstructions(OutputPolicy.SKIP) else engine.inputJarEntry(entryName)
    }

    private companion object {
        const val SIGNER_NAME = "CERT"
        const val LIBRARIES = "lib/"
        val LIBRARY = Regex("lib/([^/]+)/.+")

        // As EmbeddedJvmAssetsTask lays them out and JvmInstallation reads them
        const val JVM_ASSETS = "assets/jvm/"
        const val RUNTIME_SUFFIX = ".zip.xz"
        const val DELTA_SUFFIX = ".modules.delta.xz"
    }
}
