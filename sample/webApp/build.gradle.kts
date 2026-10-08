import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

// Plain HTML page (the browser renders every script natively), built for both Kotlin/JS and Kotlin/Wasm.
plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    js {
        browser()
        binaries.executable()
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        binaries.executable()
    }

    sourceSets {
        webMain.dependencies {
            implementation(libs.retake)
            implementation(libs.kotlinx.browser)
        }
    }
}
