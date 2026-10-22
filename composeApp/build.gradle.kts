import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
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

    // The web app: the same UI and data layer in the browser, with Room on SQLite WASM through a web worker.
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        outputModuleName.set("patrimonio")
        browser {
            commonWebpackConfig { outputFileName = "patrimonio.js" }
        }
        binaries.executable()
    }

    // mobileMain: Android and iOS, for what the browser does not have (the bundled SQLite driver, the
    // migrations of databases created by earlier app versions, files on disk).
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    applyDefaultHierarchyTemplate {
        common {
            group("mobile") {
                withAndroidTarget()
                withIos()
            }
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
            implementation(libs.datastore.preferences.core)
            implementation(libs.koin.core)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.navigation.compose)
            implementation(libs.lifecycle.viewmodel)
            implementation(libs.lifecycle.viewmodel.compose)
            implementation(libs.lifecycle.runtime.compose)
            implementation(libs.koin.compose.viewmodel)
            implementation(libs.koin.compose.viewmodel.navigation)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.cryptography.core)
            implementation(libs.cryptography.provider.optimal)
            implementation(libs.filekit.core)
            implementation(libs.filekit.dialogs.compose)
        }
        getByName("mobileMain").dependencies {
            implementation(libs.sqlite.bundled)
        }
        wasmJsMain.dependencies {
            implementation(libs.ktor.client.js)
            implementation(libs.sqlite.web)
            implementation(libs.kotlinx.browser)
            // Relative to the generated package (build/wasm/packages/patrimonio) so the lock file has no machine path.
            implementation(npm("patrimonio-sqlite-worker", "file:../../../../composeApp/web-worker"))
            // Also here because npm links the local worker package without installing its dependencies.
            implementation(npm("@sqlite.org/sqlite-wasm", "3.53.4-build2"))
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.koin.android)
            implementation(libs.ktor.client.android)
            implementation(libs.androidx.credentials)
            implementation(libs.androidx.credentials.play.services)
            implementation(libs.googleid)
            implementation(libs.androidx.biometric)
            implementation(libs.androidx.fragment)
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
    add("kspWasmJs", libs.room.compiler)
}

room3 {
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

// Build inputs (version, release signing) come from an environment variable (CI) or, failing that, a Gradle
// property (e.g. in ~/.gradle/gradle.properties for a local release build). Blank counts as unset.
fun buildSetting(name: String): String? =
    (providers.environmentVariable(name).orNull ?: providers.gradleProperty(name).orNull)?.takeIf { it.isNotBlank() }

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
        // CI passes its run number so every build installs over the previous one (Play also requires it to
        // grow with every upload). Scheme and workflow: docs/release.md.
        versionCode = buildSetting("VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = buildSetting("VERSION_NAME") ?: "1.0.0-dev"
    }

    // Store build (Play App Bundle), signed with the upload key: docs/release.md. The four values come from
    // env vars (CI decodes the keystore from a secret) or Gradle properties. When any is missing there is no
    // release signing config, and the guard below stops bundleRelease/assembleRelease before they start: an
    // unsigned bundle is useless to Play and easy to mistake for a good one. Debug, preview and
    // scripts/check.sh never need the key.
    val releaseSigningInputs = listOf(
        "RELEASE_KEYSTORE_FILE",
        "RELEASE_KEYSTORE_PASSWORD",
        "RELEASE_KEY_ALIAS",
        "RELEASE_KEY_PASSWORD",
    ).associateWith { buildSetting(it) }
    val releaseSigningMissing = releaseSigningInputs.filterValues { it == null }.keys

    // Task names that produce a store artifact (the preview variant has its own, differently named tasks).
    val releaseArtifactTasks = setOf("bundleRelease", "assembleRelease", "packageRelease", "packageReleaseBundle")
    val releaseSigningReady = releaseSigningMissing.isEmpty()
    gradle.taskGraph.whenReady {
        if (!releaseSigningReady && allTasks.any { it.project == project && it.name in releaseArtifactTasks }) {
            throw GradleException(
                "Release signing is not configured: missing ${releaseSigningMissing.joinToString()}. " +
                    "Set RELEASE_KEYSTORE_FILE, RELEASE_KEYSTORE_PASSWORD, RELEASE_KEY_ALIAS and " +
                    "RELEASE_KEY_PASSWORD as environment variables or Gradle properties (see docs/release.md).",
            )
        }
    }

    // Preview builds go to the phone through Firebase App Distribution (.github/workflows/preview.yml).
    // They need a stable key so each build updates the installed one; without the CI keystore (local
    // builds) the preview build is signed with the debug key.
    val previewKeystore = providers.environmentVariable("PREVIEW_KEYSTORE_PATH").orNull
    signingConfigs {
        if (releaseSigningReady) {
            create("release") {
                storeFile = file(releaseSigningInputs.getValue("RELEASE_KEYSTORE_FILE")!!)
                storePassword = releaseSigningInputs.getValue("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = releaseSigningInputs.getValue("RELEASE_KEY_ALIAS")
                keyPassword = releaseSigningInputs.getValue("RELEASE_KEY_PASSWORD")
            }
        }
        if (previewKeystore != null) {
            create("preview") {
                storeFile = file(previewKeystore)
                storePassword = providers.environmentVariable("PREVIEW_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("PREVIEW_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("PREVIEW_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        // R8 shrinks and obfuscates the store build. It breaks at run time, not at compile time, so the
        // preview build below is minified the same way: what reaches the phone is what reaches the store.
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
        // The release build installed next to the Android Studio one: own id, data and name (the
        // "Patrimonio β" label lives in src/androidPreview/res, the KMP folder for this build type).
        create("preview") {
            initWith(getByName("release"))
            applicationIdSuffix = ".preview"
            versionNameSuffix = "-preview"
            matchingFallbacks += "release"
            signingConfig = signingConfigs.findByName("preview") ?: signingConfigs.getByName("debug")
        }
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
