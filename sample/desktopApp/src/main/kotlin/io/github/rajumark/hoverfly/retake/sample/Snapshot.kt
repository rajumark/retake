package io.github.rajumark.hoverfly.retake.sample

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/**
 * Renders the desktop app offscreen (same Compose UI as the window, without window chrome) into a PNG:
 * `./gradlew :desktopApp:snapshot -Pout=path.png`. Used for screenshots when no display is available.
 */
fun main(args: Array<String>) {
    val out = File(args.firstOrNull() ?: "desktop.png")
    val scene = ImageComposeScene(width = 960, height = 1900, density = Density(2f)) { App("Desktop (JVM)") }
    val start = System.nanoTime()
    var t = 0L
    // Let the model load (Dispatchers.Default) and the first inference finish, advancing frames as we wait.
    repeat(60) { scene.render(t); Thread.sleep(100); t = System.nanoTime() - start }
    val image = scene.render(t)
    out.writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
    scene.close()
    println("wrote $out")
    kotlin.system.exitProcess(0)
}
