@file:OptIn(ExperimentalWasmJsInterop::class)

import io.github.rajumark.hoverfly.retake.Issue
import io.github.rajumark.hoverfly.retake.Retake
import kotlin.js.ExperimentalWasmJsInterop

// Website live demo: docs/demo/worker.js calls load() once, then run() per photo; run() returns JSON.

private var instance: Retake? = null

private fun model(): Retake = instance ?: Retake().also { instance = it }

/** Loads the bundled model. */
@JsExport
fun load() {
    model()
}

/**
 * Checks one photo. [input] holds the RGBA bytes of the canvas, one char per byte (latin-1);
 * [option] is "width,height,threshold". Returns {good, issues: [..], scores: {issue: score}}.
 */
@JsExport
fun run(input: String, option: String): String {
    val o = option.split(',')
    val w = o[0].toInt(); val h = o[1].toInt(); val th = o.getOrNull(2)?.toFloatOrNull() ?: Retake.DEFAULT_THRESHOLD
    val argb = IntArray(w * h) { i ->
        val p = i * 4
        (0xFF shl 24) or (input[p].code shl 16) or (input[p + 1].code shl 8) or input[p + 2].code
    }
    val c = model().check(argb, w, h, th)
    return buildString {
        append("{\"good\":").append(c.isGood)
        append(",\"issues\":").append(c.issues.joinToString(",", "[", "]") { "\"${it.name.lowercase()}\"" })
        append(",\"hints\":").append(c.issues.joinToString(",", "[", "]") { "\"${it.hint}\"" })
        append(",\"scores\":").append(Issue.entries.joinToString(",", "{", "}") { "\"${it.name.lowercase()}\":${c.score(it)}" })
        append('}')
    }
}

fun main() {}
