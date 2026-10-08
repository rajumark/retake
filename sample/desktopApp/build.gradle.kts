plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(projects.shared)
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.swing)
}

compose.desktop {
    application {
        mainClass = "io.github.rajumark.hoverfly.retake.sample.MainKt"
    }
}

// Offscreen render of the app into a PNG (for screenshots without a display): -Pout=<file>
tasks.register<JavaExec>("snapshot") {
    mainClass = "io.github.rajumark.hoverfly.retake.sample.SnapshotKt"
    classpath = sourceSets["main"].runtimeClasspath
    args(providers.gradleProperty("out").getOrElse("desktop.png"))
}
