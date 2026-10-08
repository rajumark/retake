package io.github.rajumark.hoverfly.retake.sample

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.rajumark.hoverfly.retake.Issue
import io.github.rajumark.hoverfly.retake.PhotoCheck
import io.github.rajumark.hoverfly.retake.Retake
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import kotlin.time.TimeSource

/** Decodes JPEG / PNG bytes, turned upright (EXIF), as a Compose image. */
expect fun decodeImage(bytes: ByteArray): ImageBitmap

private val Lime = Color(0xFF5C940D)
private val Bad = Color(0xFFC92A2A)

private class Shown(val title: String, val image: ImageBitmap, val check: PhotoCheck, val ms: Double)

/** Pixels of an ImageBitmap as ARGB ints, and the check. */
private fun run(retake: Retake, title: String, image: ImageBitmap): Shown {
    val px = IntArray(image.width * image.height)
    image.readPixels(px)
    val t = TimeSource.Monotonic.markNow()
    val c = retake.check(px, image.width, image.height)
    return Shown(title, image, c, t.elapsedNow().inWholeMicroseconds / 1000.0)
}

/**
 * The sample: example photos (or your own, where [pickPhoto] is given), each checked on the device.
 * [platform] is shown at the top, e.g. "Android".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun App(platform: String, pickPhoto: (suspend () -> ByteArray?)? = null) {
    MaterialTheme(colorScheme = lightColorScheme(primary = Lime)) {
        Surface(Modifier.fillMaxSize(), color = Color(0xFFF8F9F5)) {
            val loaded by produceState<Pair<Retake, Double>?>(null) {
                value = withContext(Dispatchers.Default) {
                    val t = TimeSource.Monotonic.markNow()
                    Retake() to t.elapsedNow().inWholeMicroseconds / 1000.0
                }
            }
            val thumbs = remember { Examples.all.map { (title, bytes) -> title to decodeImage(bytes) } }
            var shown by remember { mutableStateOf<Shown?>(null) }
            var busy by remember { mutableStateOf(false) }
            var error by remember { mutableStateOf<String?>(null) }
            val scope = rememberCoroutineScope()

            fun show(title: String, image: ImageBitmap) {
                val retake = loaded?.first ?: return
                busy = true
                scope.launch {
                    shown = withContext(Dispatchers.Default) { run(retake, title, image) }
                    busy = false
                }
            }
            LaunchedEffect(loaded) { if (loaded != null && shown == null) show(thumbs[1].first, thumbs[1].second) }

            Column(
                Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("Retake", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text("Kotlin Multiplatform · $platform · io.github.rajumark:retake:1.0.0", color = Color.Gray, fontSize = 13.sp)
                val l = loaded
                if (l == null) {
                    Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(20.dp)); Text("  Loading the model…") }
                    return@Column
                }

                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    thumbs.forEach { (title, img) ->
                        val sel = shown?.title == title
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Image(img, title, contentScale = ContentScale.Crop, modifier = Modifier.size(76.dp).clip(RoundedCornerShape(10.dp))
                                .border(BorderStroke(if (sel) 3.dp else 0.dp, Lime), RoundedCornerShape(10.dp)).clickable { show(title, img) })
                            Text(title, fontSize = 12.sp)
                        }
                    }
                }
                if (pickPhoto != null) Button(onClick = {
                    scope.launch {
                        error = null
                        val bytes = pickPhoto() ?: return@launch
                        val img = runCatching { withContext(Dispatchers.Default) { decodeImage(bytes) } }.getOrElse { error = "Can't read that file: ${it.message}"; return@launch }
                        show("Your photo", img)
                    }
                }) { Text("Check your own photo") }
                error?.let { Text(it, color = Bad) }

                val s = shown
                if (s != null) Result(s, busy)
                Text("Model loaded in ${l.second.roundToInt()} ms · runs on this device · no network, no permission", color = Color.Gray, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun Result(s: Shown, busy: Boolean) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color.White).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.fillMaxWidth().heightIn(max = 320.dp), contentAlignment = Alignment.Center) {
            Image(s.image, s.title, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp).clip(RoundedCornerShape(12.dp)))
        }
        val c = s.check
        Text(
            if (c.isGood) "✓ Good photo" else "Retake: " + c.issues.joinToString(", ") { it.name.lowercase() },
            color = if (c.isGood) Lime else Bad, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
        )
        c.issues.forEach { Text(it.hint, fontSize = 14.sp) }
        Issue.entries.forEach { issue ->
            val v = c.score(issue)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(issue.name.lowercase(), Modifier.width(64.dp), fontSize = 13.sp)
                Box(Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(5.dp)).background(Color(0xFFE9ECEF))) {
                    Box(Modifier.fillMaxWidth(v.coerceIn(0.01f, 1f)).fillMaxHeight().background(if (v >= c.threshold) Bad else Color(0xFFADB5BD)))
                }
                Text("${(v * 100).roundToInt()}%", Modifier.widthIn(min = 44.dp).padding(start = 8.dp), fontSize = 13.sp)
            }
        }
        Text(if (busy) "checking…" else "${s.image.width} × ${s.image.height} px · checked in ${(s.ms * 10).roundToInt() / 10.0} ms", color = Color.Gray, fontSize = 12.sp)
    }
}
