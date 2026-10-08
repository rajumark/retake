import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

// Live demo for the website: the library compiled to Kotlin/Wasm, run inside a Web Worker by
// docs/demo/worker.js. Build with `./gradlew :demo:publishDemo`, which refreshes docs/demo/wasm/.
plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        outputModuleName = "retake-demo"
        browser()
        binaries.executable()
    }

    sourceSets {
        wasmJsMain.dependencies { implementation(project(":retake")) }
    }
}

tasks.register<Sync>("publishDemo") {
    dependsOn("compileProductionExecutableKotlinWasmJsOptimize")
    from(layout.buildDirectory.dir("compileSync/wasmJs/main/productionExecutable/optimized")) {
        include("*.wasm", "*.mjs")
    }
    into(rootProject.layout.projectDirectory.dir("docs/demo/wasm"))
}
