package io.github.rajumark.hoverfly.retake.internal

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * An RGB photo, 3 bytes per pixel, row by row, and the preprocessing that turns it into the model's two inputs.
 * Ported 1:1 from the reference (retake/views.py, OpenCV): the whole photo squeezed to 224 x 224, and 4 crops of
 * 128 x 128 at full detail (the 3 most textured cells, where blur shows, + the flattest mid-grey cell, where noise
 * shows), from the photo at 1024 px on the long side.
 */
internal class Photo(val w: Int, val h: Int, val px: ByteArray) {

    fun crop(x0: Int, y0: Int, cw: Int, ch: Int): Photo {
        val out = ByteArray(cw * ch * 3)
        for (y in 0 until ch) px.copyInto(out, y * cw * 3, ((y0 + y) * w + x0) * 3, ((y0 + y) * w + x0 + cw) * 3)
        return Photo(cw, ch, out)
    }

    companion object {
        const val LONG = 1024
        const val WHOLE = 224
        const val CROP = 128
        const val N_CROPS = 4

        fun fromRgb(rgb: ByteArray, w: Int, h: Int): Photo {
            val (dw, dh) = target(w, h)
            if (dw == w && dh == h) return Photo(w, h, if (rgb.size == w * h * 3) rgb else rgb.copyOf(w * h * 3))
            return Photo(dw, dh, Area.resize(rgb, w, h, 3, dw, dh))
        }

        fun fromArgb(argb: IntArray, w: Int, h: Int): Photo {
            val (dw, dh) = target(w, h)
            if (dw == w && dh == h) {
                val px = ByteArray(w * h * 3)
                for (i in 0 until w * h) {
                    val c = argb[i]
                    px[i * 3] = (c shr 16).toByte(); px[i * 3 + 1] = (c shr 8).toByte(); px[i * 3 + 2] = c.toByte()
                }
                return Photo(w, h, px)
            }
            return Photo(dw, dh, Area.resizeArgb(argb, w, h, dw, dh))
        }

        /** Long side <= 1024, never enlarged (the reference rounds half away from zero, like Python's round on .5 = even; sizes never land on .5 in practice). */
        private fun target(w: Int, h: Int): Pair<Int, Int> {
            val s = LONG.toDouble() / max(w, h)
            if (s >= 1) return w to h
            return max(1, (w * s).roundToInt()) to max(1, (h * s).roundToInt())
        }
    }
}

/**
 * Resize like OpenCV's INTER_AREA: each output pixel is the area-weighted mean of the input pixels it covers,
 * rounded to the nearest integer; integer factors take OpenCV's fast path ((sum + n/2) / n).
 */
internal object Area {
    private class Tab(val src: IntArray, val dst: IntArray, val wt: FloatArray)

    private fun tab(ssize: Int, dsize: Int): Tab {
        val scale = ssize.toDouble() / dsize
        val s = ArrayList<Int>(); val d = ArrayList<Int>(); val w = ArrayList<Float>()
        for (dx in 0 until dsize) {
            val fsx1 = dx * scale
            val fsx2 = fsx1 + scale
            val cellWidth = min(scale, ssize - fsx1)
            var sx1 = ceil(fsx1).toInt()
            var sx2 = floor(fsx2).toInt()
            sx2 = min(sx2, ssize - 1)
            sx1 = min(sx1, sx2)
            if (sx1 - fsx1 > 1e-3) { s += sx1 - 1; d += dx; w += ((sx1 - fsx1) / cellWidth).toFloat() }
            for (sx in sx1 until sx2) { s += sx; d += dx; w += (1.0 / cellWidth).toFloat() }
            if (fsx2 - sx2 > 1e-3) { s += sx2; d += dx; w += (min(min(fsx2 - sx2, 1.0), cellWidth) / cellWidth).toFloat() }
        }
        return Tab(s.toIntArray(), d.toIntArray(), w.toFloatArray())
    }

    /** Channels-interleaved uint8 image (cn = 1 or 3) -> dw x dh. Shrinking only. */
    fun resize(src: ByteArray, sw: Int, sh: Int, cn: Int, dw: Int, dh: Int): ByteArray {
        require(dw <= sw && dh <= sh) { "area resize only shrinks" }
        if (sw % dw == 0 && sh % dh == 0) {
            val fx = sw / dw; val fy = sh / dh; val n = fx * fy; val half = n / 2
            val out = ByteArray(dw * dh * cn)
            val sums = IntArray(dw * cn)
            for (y in 0 until dh) {
                sums.fill(0)
                for (yy in 0 until fy) {
                    var sp = (y * fy + yy) * sw * cn
                    for (x in 0 until dw) {
                        val o = x * cn
                        for (xx in 0 until fx) { for (c in 0 until cn) sums[o + c] += src[sp + c].toInt() and 0xFF; sp += cn }
                    }
                }
                val ob = y * dw * cn
                for (i in sums.indices) out[ob + i] = ((sums[i] + half) / n).toByte()
            }
            return out
        }
        val tx = tab(sw, dw)
        return general(sh, cn, dw, dh) { sy, row ->
            val base = sy * sw * cn
            for (i in tx.src.indices) {
                val sp = base + tx.src[i] * cn; val dp = tx.dst[i] * cn; val wgt = tx.wt[i]
                for (c in 0 until cn) row[dp + c] += (src[sp + c].toInt() and 0xFF) * wgt
            }
        }
    }

    /** ARGB ints -> RGB bytes at dw x dh, without making a full-size RGB copy first. */
    fun resizeArgb(src: IntArray, sw: Int, sh: Int, dw: Int, dh: Int): ByteArray {
        require(dw <= sw && dh <= sh) { "area resize only shrinks" }
        if (sw % dw == 0 && sh % dh == 0) {
            val fx = sw / dw; val fy = sh / dh; val n = fx * fy; val half = n / 2
            val out = ByteArray(dw * dh * 3)
            val sums = IntArray(dw * 3)
            for (y in 0 until dh) {
                sums.fill(0)
                for (yy in 0 until fy) {
                    var sp = (y * fy + yy) * sw
                    for (x in 0 until dw) {
                        val o = x * 3
                        for (xx in 0 until fx) {
                            val v = src[sp++]
                            sums[o] += (v shr 16) and 0xFF; sums[o + 1] += (v shr 8) and 0xFF; sums[o + 2] += v and 0xFF
                        }
                    }
                }
                val ob = y * dw * 3
                for (i in sums.indices) out[ob + i] = ((sums[i] + half) / n).toByte()
            }
            return out
        }
        val tx = tab(sw, dw)
        return general(sh, 3, dw, dh) { sy, row ->
            val base = sy * sw
            for (i in tx.src.indices) {
                val v = src[base + tx.src[i]]; val dp = tx.dst[i] * 3; val wgt = tx.wt[i]
                row[dp] += ((v shr 16) and 0xFF) * wgt; row[dp + 1] += ((v shr 8) and 0xFF) * wgt; row[dp + 2] += (v and 0xFF) * wgt
            }
        }
    }

    /** Separable area resize in float, as OpenCV does for 8-bit images: [addRow] adds source row sy, resized horizontally. */
    private inline fun general(sh: Int, cn: Int, dw: Int, dh: Int, addRow: (Int, FloatArray) -> Unit): ByteArray {
        val ty = tab(sh, dh)
        val rowAcc = FloatArray(dw * cn)
        val acc = FloatArray(dw * dh * cn)
        var k = 0
        while (k < ty.src.size) {
            val sy = ty.src[k]
            rowAcc.fill(0f)
            addRow(sy, rowAcc)
            var j = k
            while (j < ty.src.size && ty.src[j] == sy) { // one source row can feed several output rows
                val o = ty.dst[j] * dw * cn; val wy = ty.wt[j]
                for (i in rowAcc.indices) acc[o + i] += rowAcc[i] * wy
                j++
            }
            k = j
        }
        val out = ByteArray(acc.size)
        for (i in acc.indices) {
            val v = roundHalfEven(acc[i].toDouble())
            out[i] = (if (v < 0) 0 else if (v > 255) 255 else v).toByte()
        }
        return out
    }

    /** OpenCV saturate_cast<uchar>(double) rounds half to even (cvRound). */
    private fun roundHalfEven(v: Double): Int {
        val f = floor(v)
        val diff = v - f
        return when {
            diff > 0.5 -> f.toInt() + 1
            diff < 0.5 -> f.toInt()
            else -> if (f.toInt() % 2 == 0) f.toInt() else f.toInt() + 1
        }
    }
}

/** retake/views.py: the whole view and the positions of the 4 detail crops. */
internal object Views {
    fun whole(p: Photo): Photo =
        if (p.w == Photo.WHOLE && p.h == Photo.WHOLE) p
        else Photo(Photo.WHOLE, Photo.WHOLE, resizeAny(p.px, p.w, p.h, Photo.WHOLE, Photo.WHOLE))

    /** cv2.resize INTER_AREA also enlarges (bilinear) when a side is smaller than the target. */
    private fun resizeAny(src: ByteArray, sw: Int, sh: Int, dw: Int, dh: Int): ByteArray =
        if (dw <= sw && dh <= sh) Area.resize(src, sw, sh, 3, dw, dh) else Bilinear.resize(src, sw, sh, dw, dh)

    private fun reflectPad(p: Photo): Photo { // cv2.BORDER_REFLECT (abc|cba), only for photos under 128 px
        val nw = max(p.w, Photo.CROP); val nh = max(p.h, Photo.CROP)
        fun r(i: Int, n: Int): Int {
            var v = i
            while (v < 0 || v >= n) v = if (v < 0) -v - 1 else 2 * n - v - 1
            return v
        }
        val px = ByteArray(nw * nh * 3)
        for (y in 0 until nh) for (x in 0 until nw) {
            val s = (r(y, p.h) * p.w + r(x, p.w)) * 3; val d = (y * nw + x) * 3
            px[d] = p.px[s]; px[d + 1] = p.px[s + 1]; px[d + 2] = p.px[s + 2]
        }
        return Photo(nw, nh, px)
    }

    /** -> (the photo, padded if tiny; the 4 crop corners as (y, x)), in the reference order. */
    fun positions(input: Photo): Pair<Photo, List<Pair<Int, Int>>> {
        val p = if (input.w < Photo.CROP || input.h < Photo.CROP) reflectPad(input) else input
        val w = p.w; val h = p.h
        val gray = ByteArray(w * h) // cv2.COLOR_RGB2GRAY, 8-bit fixed point
        for (i in 0 until w * h) {
            val r = p.px[i * 3].toInt() and 0xFF; val g = p.px[i * 3 + 1].toInt() and 0xFF; val b = p.px[i * 3 + 2].toInt() and 0xFF
            gray[i] = ((r * 4899 + g * 9617 + b * 1868 + 8192) shr 14).toByte()
        }
        val sw = w / 2; val sh = h / 2
        val smallB = Area.resize(gray, w, h, 1, sw, sh)
        val small = FloatArray(sw * sh) { (smallB[it].toInt() and 0xFF).toFloat() }
        // |Laplacian| (ksize 1: 4-neighbour), border BORDER_REFLECT_101
        fun r101(i: Int, n: Int) = if (n == 1) 0 else if (i < 0) -i else if (i >= n) 2 * n - i - 2 else i
        val edges = FloatArray(sw * sh)
        for (y in 0 until sh) {
            val up = r101(y - 1, sh) * sw; val dn = r101(y + 1, sh) * sw; val row = y * sw
            for (x in 0 until sw) {
                val v = small[up + x] + small[dn + x] + small[row + r101(x - 1, sw)] + small[row + r101(x + 1, sw)] - 4 * small[row + x]
                edges[row + x] = abs(v)
            }
        }
        val ny = max(h / Photo.CROP, 1); val nx = max(w / Photo.CROP, 1)
        val oy = (h - ny * Photo.CROP) / 2; val ox = (w - nx * Photo.CROP) / 2
        val c2 = Photo.CROP / 2
        val cells = ArrayList<Cell>()
        for (i in 0 until ny) for (j in 0 until nx) {
            val y = oy + i * Photo.CROP; val x = ox + j * Photo.CROP
            var es = 0.0; var ms = 0.0; var n = 0
            for (yy in y / 2 until min(y / 2 + c2, sh)) for (xx in x / 2 until min(x / 2 + c2, sw)) {
                es += edges[yy * sw + xx]; ms += small[yy * sw + xx]; n++
            }
            cells += Cell(es / max(n, 1), ms / max(n, 1) / 255.0, y, x)
        }
        val byEdge = cells.sortedBy { -it.e }
        val pick = ArrayList(byEdge.take(Photo.N_CROPS - 1))
        val flat = cells.sortedBy { it.e }.filter { it.m > 0.15 && it.m < 0.85 && it !in pick }
        pick += flat.firstOrNull() ?: byEdge[min(Photo.N_CROPS - 1, byEdge.size - 1)]
        while (pick.size < Photo.N_CROPS) pick += pick[pick.size % max(pick.size, 1)]
        return p to pick.map { it.y to it.x }
    }

    private class Cell(val e: Double, val m: Double, val y: Int, val x: Int)
}

/**
 * What cv2.resize(..., INTER_AREA) does when it is not shrinking both sides (a photo under 224 px): linear
 * interpolation with OpenCV's "area mode" coefficients and 11-bit fixed-point weights, as in OpenCV's resize.cpp.
 */
internal object Bilinear {
    private class Axis(val i0: IntArray, val i1: IntArray, val a0: IntArray, val a1: IntArray)

    private fun axis(ssize: Int, dsize: Int): Axis {
        val scale = ssize.toDouble() / dsize
        val inv = dsize.toDouble() / ssize
        val i0 = IntArray(dsize); val i1 = IntArray(dsize); val a0 = IntArray(dsize); val a1 = IntArray(dsize)
        for (d in 0 until dsize) {
            var sx = floor(d * scale).toInt()
            var fx = ((d + 1) - (sx + 1) * inv).toFloat()
            fx = if (fx <= 0f) 0f else fx - floor(fx)
            if (sx >= ssize - 1) { fx = 0f; sx = ssize - 1 }
            i0[d] = sx; i1[d] = min(sx + 1, ssize - 1)
            a1[d] = (fx * 2048f).roundToInt(); a0[d] = ((1f - fx) * 2048f).roundToInt()
        }
        return Axis(i0, i1, a0, a1)
    }

    fun resize(src: ByteArray, sw: Int, sh: Int, dw: Int, dh: Int): ByteArray {
        val ax = axis(sw, dw); val ay = axis(sh, dh)
        val out = ByteArray(dw * dh * 3)
        val r0 = IntArray(dw * 3); val r1 = IntArray(dw * 3)
        fun hrow(sy: Int, row: IntArray) {
            val base = sy * sw * 3
            for (x in 0 until dw) for (c in 0 until 3) {
                row[x * 3 + c] = (src[base + ax.i0[x] * 3 + c].toInt() and 0xFF) * ax.a0[x] +
                    (src[base + ax.i1[x] * 3 + c].toInt() and 0xFF) * ax.a1[x]
            }
        }
        for (y in 0 until dh) {
            hrow(ay.i0[y], r0); hrow(ay.i1[y], r1)
            val b0 = ay.a0[y]; val b1 = ay.a1[y]
            for (i in 0 until dw * 3) {
                // OpenCV's vectorised 8-bit vertical pass: 16-bit high multiplies of (row >> 4) by the weights, + 2, >> 2
                val v = ((((r0[i] shr 4) * b0) shr 16) + (((r1[i] shr 4) * b1) shr 16) + 2) shr 2
                out[y * dw * 3 + i] = v.coerceIn(0, 255).toByte()
            }
        }
        return out
    }
}
