import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(libs.kotlinx.datetime)
            implementation(libs.room.runtime)
            implementation(libs.sqlite.bundled)
            implementation(libs.datastore.preferences.core)
            implementation(libs.koin.core)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.navigation.compose)
            implementation(libs.lifecycle.viewmodel)
            implementation(libs.lifecycle.viewmodel.compose)
            implementation(libs.koin.compose.viewmodel)
            implementation(libs.koin.compose.viewmodel.navigation)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.koin.android)
            implementation(libs.ktor.client.android)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        val androidInstrumentedTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.androidx.test.runner)
                implementation(libs.room.testing)
                implementation(libs.sqlite.framework)
            }
        }
        val androidUnitTest by getting {
            dependencies {
                // Room's Android `inMemoryDatabaseBuilder` overload requires a real `Context`
                // (see design.md Decision #9 / Open Question a) — Robolectric supplies one for
                // plain JVM unit tests (testDebugUnitTest), no device/emulator needed.
                implementation(libs.robolectric)
                implementation(libs.androidx.test.core)
                implementation(libs.room.testing)
                // BundledSQLiteDriver's native `.so` targets Android device ABIs (Bionic libc) and
                // cannot load under a plain JVM even via Robolectric (see design.md Open Question a
                // resolution). Tests use the framework driver instead, which Robolectric shadows
                // with its own host-native SQLite; production keeps BundledSQLiteDriver for a
                // consistent SQLite version across OEM devices.
                implementation(libs.sqlite.framework)
            }
        }
    }
}

dependencies {
    add("kspAndroid", libs.room.compiler)
    add("kspIosArm64", libs.room.compiler)
    add("kspIosSimulatorArm64", libs.room.compiler)
}

room {
    schemaDirectory("$projectDir/schemas")
}

compose.resources {
    publicResClass = true
    packageOfResClass = "com.denebapps.patrimonio.resources"
}

// The Compose Multiplatform resource generator adds generated accessor/collector
// sources into the Kotlin source sets; keep ktlint scoped to hand-written code.
ktlint {
    filter {
        exclude("**/build/generated/**")
        exclude { element -> element.file.path.replace('\\', '/').contains("/build/generated/") }
    }
}

android {
    namespace = "com.denebapps.patrimonio"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()

    sourceSets["debug"].assets.srcDir("$projectDir/schemas")
    sourceSets["androidTest"].assets.srcDir("$projectDir/schemas")

    defaultConfig {
        applicationId = "com.denebapps.patrimonio"
        minSdk = libs.versions.androidMinSdk.get().toInt()
        targetSdk = libs.versions.androidTargetSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    lint {
        // AGP 8.7.3's bundled lint-vital crashes on this toolchain (Kotlin 2.2.0 analysis-api
        // vs. the androidx.lifecycle lint-checks jar's expected 2.0.0 metadata version) —
        // an environment/AGP-lint-checks incompatibility unrelated to design-system's code,
        // reproducible even against composeApp's pre-change baseline. Disabling this one
        // detector unblocks `assembleRelease`; full lint (ktlint + compiler) still runs.
        disable += "NullSafeMutableLiveData"
        checkReleaseBuilds = false
    }
}
