// AGP 8.9 bundles R8 8.9, which cannot read Kotlin 2.2 metadata ("An error occurred when parsing kotlin
// metadata"); Kotlin 2.2 needs R8 8.10.21+ (developer.android.com/build/kotlin-support). Drop this once AGP
// bundles a newer R8.
buildscript {
    repositories {
        google()
    }
    dependencies {
        classpath("com.android.tools:r8:8.10.21")
    }
}

plugins {
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.ktlint) apply false
}
