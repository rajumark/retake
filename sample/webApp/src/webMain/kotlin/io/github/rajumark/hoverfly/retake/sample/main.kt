package io.github.rajumark.hoverfly.retake.sample

import io.github.rajumark.hoverfly.retake.Issue
import io.github.rajumark.hoverfly.retake.Retake
import kotlinx.browser.document
import org.khronos.webgl.get
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLImageElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.url.URL
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.time.TimeSource

/** "Kotlin/JS" or "Kotlin/Wasm". */
expect val runtime: String

private val examples = listOf("good" to "Good", "blur" to "Blurry", "dark" to "Dark", "bright" to "Too bright",
    "glare" to "Glare", "noise" to "Noisy", "finger" to "Finger", "screenshot" to "Screenshot")

fun main() {
    fun el(id: String) = document.getElementById(id) as HTMLElement
    el("platform").textContent = "Kotlin Multiplatform · $runtime · io.github.rajumark:retake:1.0.0"

    val t0 = TimeSource.Monotonic.markNow()
    val retake = Retake()
    el("load").textContent = "Model loaded in ${t0.elapsedNow().inWholeMilliseconds} ms"

    val work = document.getElementById("work") as HTMLCanvasElement
    val photo = document.getElementById("photo") as HTMLImageElement

    // Draws the photo at <= 1024 px on the long side and checks its pixels.
    fun check() {
        val s = minOf(1.0, 1024.0 / max(photo.naturalWidth, photo.naturalHeight))
        val w = max(1, (photo.naturalWidth * s).roundToInt()); val h = max(1, (photo.naturalHeight * s).roundToInt())
        work.width = w; work.height = h
        val g = work.getContext("2d") as CanvasRenderingContext2D
        g.drawImage(photo, 0.0, 0.0, w.toDouble(), h.toDouble())
        val data = g.getImageData(0.0, 0.0, w.toDouble(), h.toDouble()).data
        val argb = IntArray(w * h) { i ->
            val p = i * 4
            (0xFF shl 24) or ((data[p].toInt() and 0xFF) shl 16) or ((data[p + 1].toInt() and 0xFF) shl 8) or (data[p + 2].toInt() and 0xFF)
        }
        val mark = TimeSource.Monotonic.markNow()
        val c = retake.check(argb, w, h)
        val ms = mark.elapsedNow().inWholeMilliseconds
        val v = el("verdict")
        v.className = if (c.isGood) "verdict good" else "verdict bad"
        v.textContent = if (c.isGood) "✓ Good photo" else "Retake: " + c.issues.joinToString(", ") { it.name.lowercase() }
        el("hints").innerHTML = c.issues.joinToString("") { "<p class=\"hint\">${it.hint}</p>" }
        el("bars").innerHTML = Issue.entries.joinToString("") { i ->
            val p = (c.score(i) * 100).roundToInt()
            "<div class=\"bar\"><span class=\"name\">${i.name.lowercase()}</span><div class=\"track\"><div class=\"fill${if (c.score(i) >= c.threshold) " hit" else ""}\" style=\"width:$p%\"></div></div><span class=\"pct\">$p%</span></div>"
        }
        el("timing").textContent = "$w × $h px" + if (ms > 0) " · checked in $ms ms" else "" // a headless browser's frozen clock reads 0
    }
    photo.onload = { check(); null }

    el("examples").innerHTML = examples.joinToString("") { (f, t) -> "<button class=\"thumb\" data-f=\"$f\"><img src=\"examples/$f.jpg\" alt=\"\">$t</button>" }
    val buttons = el("examples").querySelectorAll("button")
    fun select(i: Int) {
        for (k in 0 until buttons.length) (buttons.item(k) as HTMLElement).className = if (k == i) "thumb sel" else "thumb"
    }
    for (i in 0 until buttons.length) {
        val b = buttons.item(i) as HTMLElement
        b.onclick = { select(i); photo.src = "examples/${b.getAttribute("data-f")}.jpg"; null }
    }
    val file = document.getElementById("file") as HTMLInputElement
    file.onchange = {
        file.files?.item(0)?.let { f -> select(-1); photo.src = URL.createObjectURL(f) }
        null
    }
    select(1)
    photo.src = "examples/blur.jpg"
}
