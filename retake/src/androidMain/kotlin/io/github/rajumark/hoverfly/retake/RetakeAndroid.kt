@file:JvmName("RetakeAndroid")

package io.github.rajumark.hoverfly.retake

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import kotlin.math.max

/**
 * Checks a [Bitmap] (any config; it is read as ARGB). Large bitmaps are shrunk to 1024 px on the long side first.
 * The bitmap should already be upright (rotated by its EXIF orientation), as it is when it comes from the camera
 * preview or from [check] with a [Uri].
 */
@JvmOverloads
public fun Retake.check(bitmap: Bitmap, threshold: Float = Retake.DEFAULT_THRESHOLD): PhotoCheck {
    val w = bitmap.width; val h = bitmap.height
    val px = IntArray(w * h)
    bitmap.getPixels(px, 0, w, 0, 0, w, h)
    return check(px, w, h, threshold)
}

/**
 * Checks a photo file or `content://` Uri (e.g. from the photo picker or MediaStore). Decodes at 1/2, 1/4 or 1/8 size
 * when that still gives at least 1000 px, which makes large camera photos several times faster to check, and turns the
 * photo upright from its EXIF orientation. Blocking: call it off the main thread.
 */
@JvmOverloads
public fun Retake.check(context: Context, uri: Uri, threshold: Float = Retake.DEFAULT_THRESHOLD): PhotoCheck {
    val cr = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    cr.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
    val ow = bounds.outWidth; val oh = bounds.outHeight
    require(ow > 0 && oh > 0) { "cannot read the image size of $uri" }
    var sample = 1
    while (max(ow, oh) / (sample * 2) >= NEAR_LONG_SIDE) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
    val bmp = cr.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, opts) } ?: error("cannot decode $uri")
    val w = bmp.width; val h = bmp.height
    val px = IntArray(w * h)
    bmp.getPixels(px, 0, w, 0, 0, w, h)
    bmp.recycle()
    val orientation = if (Build.VERSION.SDK_INT >= 24) {
        runCatching { cr.openInputStream(uri)!!.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) } }.getOrDefault(1)
    } else 1
    if (orientation in 2..8) {
        val (rotated, rw, rh) = upright(px, w, h, orientation)
        return check(rotated, rw, rh, threshold)
    }
    return check(px, w, h, threshold)
}

private const val NEAR_LONG_SIDE = 1000

/** Applies an EXIF orientation (2..8) to ARGB pixels. */
private fun upright(px: IntArray, w: Int, h: Int, o: Int): Triple<IntArray, Int, Int> {
    val swap = o >= 5
    val nw = if (swap) h else w; val nh = if (swap) w else h
    val last = (h - 1) * w
    // source index of output (x, y) = start + x * stepX + y * stepY
    val (start, stepX, stepY) = when (o) {
        2 -> Triple(w - 1, -1, w)
        3 -> Triple(last + w - 1, -1, -w)
        4 -> Triple(last, 1, -w)
        5 -> Triple(0, w, 1)
        6 -> Triple(last, -w, 1)
        7 -> Triple(last + w - 1, -w, -1)
        else -> Triple(w - 1, w, -1)
    }
    val out = IntArray(px.size)
    var d = 0
    for (y in 0 until nh) {
        var s = start + y * stepY
        for (x in 0 until nw) { out[d++] = px[s]; s += stepX }
    }
    return Triple(out, nw, nh)
}
