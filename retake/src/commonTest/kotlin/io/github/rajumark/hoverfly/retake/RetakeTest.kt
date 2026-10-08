package io.github.rajumark.hoverfly.retake

import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.TimeSource

class RetakeTest {
    private val retake = ParityTest.testRetake()
    private fun vector(name: String) = ParityTest.vectors().first { it.name == name }

    @Test
    fun scoresAreProbabilitiesForEveryIssue() {
        val v = vector("big_resized")
        val c = retake.checkRgb(v.rgb, v.w, v.h)
        assertEquals(Issue.entries.toSet(), c.scores.keys)
        assertTrue(c.scores.values.all { it in 0f..1f })
    }

    @Test
    fun cleanPhotoIsGoodAndDarkPhotoIsDark() {
        val big = vector("big_resized")
        assertTrue(retake.checkRgb(big.rgb, big.w, big.h).isGood)
        // the same photo, darkened a lot
        val dark = ByteArray(big.rgb.size) { ((big.rgb[it].toInt() and 0xFF) / 12).toByte() }
        val c = retake.checkRgb(dark, big.w, big.h)
        println("darkened: $c")
        assertTrue(Issue.DARK in c.issues, c.toString())
    }

    @Test
    fun thresholdMovesTheLine() {
        val v = vector("glare_4")
        val c = retake.checkRgb(v.rgb, v.w, v.h)
        val strongest = c.strongest.second
        assertTrue(retake.checkRgb(v.rgb, v.w, v.h, threshold = 0f).issues.size == Issue.entries.size)
        assertTrue(retake.checkRgb(v.rgb, v.w, v.h, threshold = minOf(1f, strongest + 0.001f)).isGood)
        assertEquals(c.issues, c.issues.sortedByDescending { c.score(it) })
    }

    @Test
    fun tinyAndOddSizesWork() {
        for ((w, h) in listOf(1 to 1, 7 to 300, 300 to 5, 128 to 128, 1025 to 1, 3000 to 2000)) {
            val px = IntArray(w * h) { (0xFF shl 24) or ((it * 31) and 0xFFFFFF) }
            val c = retake.check(px, w, h)
            assertEquals(6, c.scores.size, "$w x $h")
        }
    }

    @Test
    fun badInputIsRejected() {
        assertFailsWith<IllegalArgumentException> { retake.check(IntArray(10), 5, 5) }
        assertFailsWith<IllegalArgumentException> { retake.check(IntArray(0), 0, 0) }
        assertFailsWith<IllegalArgumentException> { retake.checkRgb(ByteArray(10), 2, 2) }
    }

    @Test
    fun closedInstanceThrows() {
        val r = Retake()
        r.close()
        assertFailsWith<IllegalStateException> { r.check(IntArray(4), 2, 2) }
    }

    @Test
    fun latency() {
        val v = vector("big_resized") // 1040 x 780: also measures the shrink to 1024
        val argb = IntArray(v.w * v.h) { i ->
            (0xFF shl 24) or ((v.rgb[i * 3].toInt() and 0xFF) shl 16) or ((v.rgb[i * 3 + 1].toInt() and 0xFF) shl 8) or (v.rgb[i * 3 + 2].toInt() and 0xFF)
        }
        repeat(3) { retake.check(argb, v.w, v.h) }
        val n = 10
        val t0 = TimeSource.Monotonic.markNow()
        repeat(n) { retake.check(argb, v.w, v.h) }
        val ms = t0.elapsedNow().inWholeMicroseconds / 1000.0 / n
        println("latency: ${(ms * 10).roundToInt() / 10.0} ms per photo (1040 x 780, whole pipeline)")
        val l0 = TimeSource.Monotonic.markNow()
        Retake().close()
        println("load: ${l0.elapsedNow().inWholeMilliseconds} ms")
    }
}
