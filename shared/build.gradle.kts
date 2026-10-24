// The tracking code shared by the Android and iOS apps: finding the ball at the strike, following it,
// fitting its flight, the launch physics, and the shot model.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    // Same Java version as the app, whose JVM tests run this code.
    jvmToolchain(17)

    androidLibrary {
        namespace = "com.golftracker.shared"
        compileSdk = 36
        minSdk = 26
    }
    // iPhone, the iPhone simulator, and this Mac (to run the shared tests natively).
    listOf(iosArm64(), iosSimulatorArm64()).forEach {
        it.binaries.framework {
            baseName = "GolfTracking"
            isStatic = true
        }
    }
    macosArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
