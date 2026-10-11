// AGP 9 compiles Kotlin itself ("built-in Kotlin"); declaring the Kotlin plugin here
// only pins the Kotlin compiler version that AGP uses.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}
