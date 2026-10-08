package io.github.rajumark.hoverfly.retake.sample

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.SwingUtilities
import kotlinx.coroutines.CompletableDeferred

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "Retake (desktop)", state = rememberWindowState(position = WindowPosition(40.dp, 40.dp), width = 800.dp, height = 600.dp)) {
        App("Desktop (JVM)") {
            val d = CompletableDeferred<ByteArray?>()
            SwingUtilities.invokeLater {
                val fd = FileDialog(null as Frame?, "Choose a photo", FileDialog.LOAD)
                fd.setFilenameFilter { _, n -> n.lowercase().substringAfterLast('.') in setOf("jpg", "jpeg", "png", "webp", "bmp") }
                fd.isVisible = true
                d.complete(fd.file?.let { File(fd.directory, it).readBytes() })
            }
            d.await()
        }
    }
}
