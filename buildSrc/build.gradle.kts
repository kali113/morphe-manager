plugins {
    `kotlin-dsl`
}

repositories {
    google()
    mavenCentral()
}

dependencies {
    // The Android builds of the JVM come as .tar.xz, which Gradle cannot unpack on its own
    implementation(libs.xz)

    // Signs the APK of each ABI, in the version the Android Gradle plugin signs with itself
    implementation(libs.apksig)
}
