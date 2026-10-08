package io.github.rajumark.hoverfly.retake

import io.github.rajumark.hoverfly.retake.internal.Photo
import io.github.rajumark.hoverfly.retake.internal.TestData
import io.github.rajumark.hoverfly.retake.internal.Views
import io.github.rajumark.hoverfly.retake.internal.decodeChunks
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Checks the Kotlin port against the reference (the same int8 weights run in PyTorch fp32, after OpenCV
 * preprocessing) on testvectors.bin: 13 raw RGB images of every kind, incl. one that has to be shrunk to 1024 px and
 * one under 128 px. Requires the same 4 crop positions and every score within 0.002. Runs on every target.
 */
class ParityTest {
    class Vector(val name: String, val w: Int, val h: Int, val rgb: ByteArray, val crops: List<Pair<Int, Int>>, val scores: FloatArray)

    @Test
    fun cropsMatchReference() {
        var bad = 0
        for (v in vectors()) {
            val (_, pos) = Views.positions(Photo.fromRgb(v.rgb, v.w, v.h))
            if (pos != v.crops) { bad++; println("CROPS ${v.name}: got $pos want ${v.crops}") }
        }
        println("crops: ${vectors().size - bad}/${vectors().size} identical")
        assertEquals(0, bad)
    }

    @Test
    fun scoresMatchReference() {
        val retake = testRetake()
        var maxDiff = 0f
        for (v in vectors()) {
            val got = retake.checkRgb(v.rgb, v.w, v.h)
            val d = Issue.entries.maxOf { abs(got.score(it) - v.scores[it.ordinal]) }
            if (d > 0.002f) println("SCORES ${v.name}: diff $d got $got want ${v.scores.toList()}")
            maxDiff = maxOf(maxDiff, d)
        }
        println("scores: max diff $maxDiff over ${vectors().size} images")
        assertTrue(maxDiff < 0.002f, "scores differ by $maxDiff")
    }

    @Test
    fun argbGivesTheSameAsRgb() {
        val retake = testRetake()
        for (v in vectors().take(4)) {
            val argb = IntArray(v.w * v.h) { i ->
                (0xFF shl 24) or ((v.rgb[i * 3].toInt() and 0xFF) shl 16) or ((v.rgb[i * 3 + 1].toInt() and 0xFF) shl 8) or (v.rgb[i * 3 + 2].toInt() and 0xFF)
            }
            val a = retake.check(argb, v.w, v.h); val b = retake.checkRgb(v.rgb, v.w, v.h)
            for (i in Issue.entries) assertEquals(b.score(i), a.score(i), "${v.name} $i")
        }
    }

    companion object {
        private var shared: Retake? = null

        /** One instance per test run: loading is the slow part on the native and web targets. */
        fun testRetake(): Retake = shared ?: Retake().also { shared = it }

        private var cached: List<Vector>? = null

        /** testvectors.bin: "RTKV", count; per image: name, w, h, RGB bytes, 4 x (y, x), 6 fp32 scores. */
        fun vectors(): List<Vector> = cached ?: run {
            val b = decodeChunks(TestData.files.getValue("testvectors.bin"))
            var p = 0
            fun int(): Int {
                val v = (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8) or
                    ((b[p + 2].toInt() and 0xFF) shl 16) or ((b[p + 3].toInt() and 0xFF) shl 24)
                p += 4
                return v
            }
            fun bytes(n: Int): ByteArray = b.copyOfRange(p, p + n).also { p += n }
            check(bytes(4).decodeToString() == "RTKV") { "not a testvectors.bin file" }
            List(int()) {
                val nameLen = (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8)
                p += 2
                val name = bytes(nameLen).decodeToString()
                val w = int(); val h = int()
                val rgb = bytes(w * h * 3)
                val crops = List(4) { int() to int() }
                val scores = FloatArray(6) { Float.fromBits(int()) }
                Vector(name, w, h, rgb, crops, scores)
            }
        }.also { cached = it }
    }
}
