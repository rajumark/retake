package io.github.rajumark.hoverfly.retake.internal

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * The Retake network in plain Kotlin: two small MobileNet-style CNNs and a head.
 *
 *   whole:  the whole photo at 224 x 224 -> stem 3x3 conv -> inverted residual blocks -> 1x1 conv -> 7 x 7 x 192
 *   detail: each of the 4 crops (128 x 128) -> the same kind of trunk -> 8 x 8 x 128
 *   head:   [mean, max] of the whole features ++ [mean, max] over crops of each crop's [mean, max]
 *           -> Linear 896 -> 192, ReLU -> Linear 192 -> 6 -> sigmoid
 *
 * Batch-norm is folded into the convolutions; weights are int8 with one scale per output channel, dequantized to
 * float at load. Tensors are channel-major (C x H x W). Stateless after loading: one instance can serve several threads.
 */
internal class Network(bin: ByteArray) {

    private class Conv(val cout: Int, val cin: Int, val k: Int, val stride: Int, val groups: Int, val w: FloatArray, val b: FloatArray)

    /** Inverted residual block: 1x1 expand + ReLU6, 3x3 depthwise + ReLU6, 1x1 project, (+ input). */
    private class Block(val expand: Conv, val dw: Conv, val project: Conv) {
        val residual = dw.stride == 1 && project.cout == expand.cin
    }

    private class Trunk(val stem: Conv, val blocks: List<Block>, val last: Conv)

    private class Dense(val rows: Int, val cols: Int, val w: FloatArray, val b: FloatArray)

    private val whole: Trunk
    private val detail: Trunk
    private val fc1: Dense
    private val fc2: Dense

    init {
        val t = read(bin)
        fun conv(name: String): Conv {
            val m = t["$name.weight"] as? Matrix ?: error("retake.bin: missing $name.weight")
            val b = t["$name.bias"] as? FloatArray ?: error("retake.bin: missing $name.bias")
            val cfg = t["$name.cfg"] as? FloatArray ?: error("retake.bin: missing $name.cfg")
            val k = cfg[0].toInt(); val stride = cfg[1].toInt(); val groups = cfg[2].toInt()
            return Conv(m.rows, m.cols / (k * k) * groups, k, stride, groups, m.dense(), b)
        }
        fun trunk(prefix: String): Trunk {
            val names = t.keys.filter { it.startsWith("$prefix.") && it.endsWith(".weight") }
                .map { it.removeSuffix(".weight") }.sortedBy { it.substringAfter('.').toInt() }
            val convs = names.map(::conv)
            require(convs.size >= 2 && (convs.size - 2) % 3 == 0) { "retake.bin: unexpected $prefix layout" }
            val blocks = (1 until convs.size - 1 step 3).map { Block(convs[it], convs[it + 1], convs[it + 2]) }
            return Trunk(convs.first(), blocks, convs.last())
        }
        fun dense(name: String): Dense {
            val m = t["$name.weight"] as? Matrix ?: error("retake.bin: missing $name.weight")
            return Dense(m.rows, m.cols, m.dense(), t["$name.bias"] as? FloatArray ?: error("retake.bin: missing $name.bias"))
        }
        whole = trunk("whole")
        detail = trunk("detail")
        fc1 = dense("head.0")
        fc2 = dense("head.3")
    }

    /** The 6 issue scores (0..1) for a photo already at <= 1024 px on the long side. */
    fun scores(photo: Photo): FloatArray {
        val wv = Views.whole(photo)
        val (padded, pos) = Views.positions(photo)
        val fw = pool(run(whole, chw(wv), Photo.WHOLE, Photo.WHOLE))
        val crops = pos.map { (y, x) -> pool(run(detail, chw(padded.crop(x, y, Photo.CROP, Photo.CROP)), Photo.CROP, Photo.CROP)) }
        val dd = crops[0].size
        val mean = FloatArray(dd); val mx = FloatArray(dd) { Float.NEGATIVE_INFINITY }
        for (c in crops) for (i in 0 until dd) { mean[i] += c[i]; mx[i] = max(mx[i], c[i]) }
        for (i in 0 until dd) mean[i] /= crops.size
        val feat = fw + mean + mx
        val h = linear(fc1, feat)
        for (i in h.indices) if (h[i] < 0f) h[i] = 0f
        val logits = linear(fc2, h)
        return FloatArray(logits.size) { (1.0 / (1.0 + exp(-logits[it].toDouble()))).toFloat() }
    }

    /** RGB bytes -> normalised float C x H x W: (v / 255 - 0.5) / 0.25. */
    private fun chw(p: Photo): FloatArray {
        val n = p.w * p.h
        val out = FloatArray(3 * n)
        for (i in 0 until n) for (c in 0 until 3) out[c * n + i] = ((p.px[i * 3 + c].toInt() and 0xFF) / 255f - 0.5f) / 0.25f
        return out
    }

    /** Runs a trunk on a C x H x W input; returns its last feature map. */
    private fun run(t: Trunk, input: FloatArray, h: Int, w: Int): Feature {
        var f = Feature(conv(t.stem, input, h, w, relu6 = true), t.stem.cout, out(h, t.stem.stride), out(w, t.stem.stride))
        for (b in t.blocks) {
            val e = Feature(pointwise(b.expand, f.data, f.h * f.w, relu6 = true), b.expand.cout, f.h, f.w)
            val d = Feature(depthwise(b.dw, e.data, e.h, e.w), b.dw.cout, out(e.h, b.dw.stride), out(e.w, b.dw.stride))
            val p = pointwise(b.project, d.data, d.h * d.w, relu6 = false)
            if (b.residual) for (i in p.indices) p[i] += f.data[i]
            f = Feature(p, b.project.cout, d.h, d.w)
        }
        return Feature(pointwise(t.last, f.data, f.h * f.w, relu6 = true), t.last.cout, f.h, f.w)
    }

    private class Feature(val data: FloatArray, val c: Int, val h: Int, val w: Int)

    private fun out(n: Int, stride: Int) = (n + 2 - 3) / stride + 1 // 3x3, padding 1

    /** [mean over H x W, max over H x W] per channel. */
    private fun pool(f: Feature): FloatArray {
        val n = f.h * f.w
        val out = FloatArray(2 * f.c)
        for (c in 0 until f.c) {
            var s = 0f; var m = Float.NEGATIVE_INFINITY
            val o = c * n
            for (i in 0 until n) { val v = f.data[o + i]; s += v; if (v > m) m = v }
            out[c] = s / n; out[f.c + c] = m
        }
        return out
    }

    /** Dense 3x3 convolution with padding 1 (the stem: 3 input channels). */
    private fun conv(cv: Conv, x: FloatArray, h: Int, w: Int, relu6: Boolean): FloatArray {
        val oh = out(h, cv.stride); val ow = out(w, cv.stride); val s = cv.stride
        val y = FloatArray(cv.cout * oh * ow)
        for (co in 0 until cv.cout) {
            val yo = co * oh * ow
            y.fill(cv.b[co], yo, yo + oh * ow)
            for (ci in 0 until cv.cin) {
                val xo = ci * h * w
                for (ky in 0 until 3) for (kx in 0 until 3) {
                    val wv = cv.w[((co * cv.cin + ci) * 3 + ky) * 3 + kx]
                    for (oy in 0 until oh) {
                        val iy = oy * s + ky - 1
                        if (iy < 0 || iy >= h) continue
                        val row = xo + iy * w; val orow = yo + oy * ow
                        val ox0 = if (kx == 0) 1 else 0 // ix = ox * s + kx - 1 >= 0
                        val oxMax = min(ow - 1, (w - kx) / s) // ix <= w - 1
                        for (ox in ox0..oxMax) y[orow + ox] += wv * x[row + ox * s + kx - 1]
                    }
                }
            }
        }
        if (relu6) clamp(y)
        return y
    }

    /**
     * 1x1 convolution: y[co] = b[co] + sum_ci w[co, ci] * x[ci], over n positions. Each output row is summed in its
     * own array, 4 input channels per pass, so the JIT / compilers can vectorise the inner loop.
     */
    private fun pointwise(cv: Conv, x: FloatArray, n: Int, relu6: Boolean): FloatArray {
        val y = FloatArray(cv.cout * n)
        val acc = FloatArray(n)
        val cin = cv.cin
        for (co in 0 until cv.cout) {
            acc.fill(cv.b[co])
            val wo = co * cin
            var ci = 0
            while (ci + 3 < cin) {
                val w0 = cv.w[wo + ci]; val w1 = cv.w[wo + ci + 1]; val w2 = cv.w[wo + ci + 2]; val w3 = cv.w[wo + ci + 3]
                val x0 = ci * n; val x1 = x0 + n; val x2 = x1 + n; val x3 = x2 + n
                for (i in 0 until n) acc[i] += w0 * x[x0 + i] + w1 * x[x1 + i] + w2 * x[x2 + i] + w3 * x[x3 + i]
                ci += 4
            }
            while (ci < cin) {
                val wv = cv.w[wo + ci]; val xo = ci * n
                for (i in 0 until n) acc[i] += wv * x[xo + i]
                ci++
            }
            val yo = co * n
            if (relu6) for (i in 0 until n) y[yo + i] = min(max(acc[i], 0f), 6f)
            else acc.copyInto(y, yo)
        }
        return y
    }

    /** Depthwise 3x3 convolution with padding 1, then ReLU6. */
    private fun depthwise(cv: Conv, x: FloatArray, h: Int, w: Int): FloatArray {
        val s = cv.stride
        val oh = out(h, s); val ow = out(w, s)
        val y = FloatArray(cv.cout * oh * ow)
        for (c in 0 until cv.cout) {
            val xo = c * h * w; val yo = c * oh * ow; val wo = c * 9; val bias = cv.b[c]
            for (oy in 0 until oh) {
                for (ox in 0 until ow) {
                    var acc = bias
                    val iy0 = oy * s - 1; val ix0 = ox * s - 1
                    for (ky in 0 until 3) {
                        val iy = iy0 + ky
                        if (iy < 0 || iy >= h) continue
                        val row = xo + iy * w
                        for (kx in 0 until 3) {
                            val ix = ix0 + kx
                            if (ix < 0 || ix >= w) continue
                            acc += cv.w[wo + ky * 3 + kx] * x[row + ix]
                        }
                    }
                    y[yo + oy * ow + ox] = acc
                }
            }
        }
        clamp(y)
        return y
    }

    /** ReLU6, branch-free (min / max compile to single instructions; an if/else here cost 18% of the time). */
    private fun clamp(y: FloatArray) {
        for (i in y.indices) y[i] = min(max(y[i], 0f), 6f)
    }

    private fun linear(d: Dense, x: FloatArray): FloatArray = FloatArray(d.rows) { r ->
        var s = d.b[r]
        val o = r * d.cols
        for (c in 0 until d.cols) s += d.w[o + c] * x[c]
        s
    }

    /** Row-wise symmetric int8 matrix: w[r][c] = scale[r] * q[r * cols + c]. */
    private class Matrix(val rows: Int, val cols: Int, val scale: FloatArray, val q: ByteArray) {
        fun dense(): FloatArray = FloatArray(rows * cols) { scale[it / cols] * q[it] }
    }

    private companion object {
        fun read(bytes: ByteArray): Map<String, Any> {
            val buf = LittleEndianReader(bytes)
            require(buf.bytes(4).decodeToString() == "MOJI") { "not a retake.bin file" }
            val version = buf.int
            require(version == 1) { "unsupported retake.bin version $version" }
            val out = HashMap<String, Any>()
            repeat(buf.int) {
                val name = buf.bytes(buf.short.toInt() and 0xFFFF).decodeToString()
                val dtype = buf.byte.toInt()
                val dims = IntArray(buf.byte.toInt()) { buf.int }
                val size = dims.fold(1) { a, b -> a * b }
                out[name] = when (dtype) {
                    0 -> FloatArray(size) { buf.float }
                    1 -> {
                        val scale = FloatArray(dims[0]) { buf.float }
                        Matrix(dims[0], dims[1], scale, buf.bytes(size))
                    }
                    else -> error("retake.bin: unknown dtype $dtype for $name")
                }
            }
            return out
        }
    }

    /** Sequential little-endian reads over a byte array (java.nio.ByteBuffer is JVM-only). */
    private class LittleEndianReader(private val b: ByteArray) {
        private var pos = 0

        val byte: Byte get() = b[pos++]

        val int: Int get() = (b[pos++].toInt() and 0xFF) or ((b[pos++].toInt() and 0xFF) shl 8) or
            ((b[pos++].toInt() and 0xFF) shl 16) or ((b[pos++].toInt() and 0xFF) shl 24)

        val short: Short get() = ((b[pos++].toInt() and 0xFF) or ((b[pos++].toInt() and 0xFF) shl 8)).toShort()

        val float: Float get() = Float.fromBits(int)

        fun bytes(n: Int): ByteArray = b.copyOfRange(pos, pos + n).also { pos += n }
    }
}
