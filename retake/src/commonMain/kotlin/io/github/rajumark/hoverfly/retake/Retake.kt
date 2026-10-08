package io.github.rajumark.hoverfly.retake

import io.github.rajumark.hoverfly.retake.internal.Network
import io.github.rajumark.hoverfly.retake.internal.Photo
import io.github.rajumark.hoverfly.retake.internal.readModelFile
import kotlin.jvm.JvmOverloads

/**
 * On-device photo quality checker: says whether a photo is good or should be retaken, and why. It looks for six
 * problems: [Issue.BLUR], [Issue.DARK], [Issue.BRIGHT] (over-exposed), [Issue.GLARE], [Issue.NOISE] and
 * [Issue.FINGER] (a finger over the lens).
 *
 * ```
 * Retake().use { retake ->
 *     val check = retake.check(pixels, width, height)   // ARGB pixels, e.g. from a Bitmap or a camera frame
 *     if (!check.isGood) showHint(check.issues)           // e.g. [BLUR]: "Hold still and tap to focus"
 * }
 * ```
 *
 * Screenshots, documents and graphics count as good: they are not camera photos and have nothing to retake.
 *
 * Everything runs locally on Android, iOS, macOS, the JVM and the web: the model ships inside the library, there is
 * no network, no permission and no dependency. Creating an instance reads the model (tens of ms), so create it off the
 * main thread and keep it around; [check] is safe to call from several threads.
 */
public class Retake internal constructor(open: (String) -> ByteArray) : AutoCloseable {

    /** Loads the model bundled in the library. */
    public constructor() : this(::readModelFile)

    private var network: Network? = Network(open("retake.bin"))

    /**
     * Checks a photo given as ARGB pixels (`0xAARRGGBB`, row by row: what `Bitmap.getPixels`, `BufferedImage.getRGB`
     * and Compose `ImageBitmap.readPixels` return; alpha is ignored). Any size works: photos larger than 1024 px on
     * the long side are shrunk first (area averaging), smaller ones are used as they are.
     *
     * [threshold] is the line a score has to reach to count as a problem: 0.5 by default, higher = only clear problems.
     */
    @JvmOverloads
    public fun check(argb: IntArray, width: Int, height: Int, threshold: Float = DEFAULT_THRESHOLD): PhotoCheck {
        require(width > 0 && height > 0) { "width and height must be positive" }
        require(argb.size >= width * height) { "argb has ${argb.size} pixels, needs width * height = ${width * height}" }
        return result(Photo.fromArgb(argb, width, height), threshold)
    }

    /**
     * Checks a photo given as RGB bytes, 3 per pixel, row by row (red, green, blue). Same rules as the ARGB [check].
     */
    @JvmOverloads
    public fun checkRgb(rgb: ByteArray, width: Int, height: Int, threshold: Float = DEFAULT_THRESHOLD): PhotoCheck {
        require(width > 0 && height > 0) { "width and height must be positive" }
        require(rgb.size >= width * height * 3) { "rgb has ${rgb.size} bytes, needs width * height * 3 = ${width * height * 3}" }
        return result(Photo.fromRgb(rgb, width, height), threshold)
    }

    private fun result(photo: Photo, threshold: Float): PhotoCheck {
        val net = checkNotNull(network) { "Retake is closed" }
        val scores = net.scores(photo)
        return PhotoCheck(Issue.entries.associateWith { scores[it.ordinal] }, threshold)
    }

    /** Releases the model. The instance cannot be used afterwards. */
    override fun close() {
        network = null
    }

    public companion object {
        /** The default line: a score of 0.5 or more counts as a problem. */
        public const val DEFAULT_THRESHOLD: Float = 0.5f
    }
}

/** A problem that makes a photo worth retaking. */
public enum class Issue(
    /** A short hint to show the user. */
    public val hint: String,
) {
    /** Out of focus, or moved while taking it (motion blur, hand shake). */
    BLUR("Blurry: hold still and tap to focus"),

    /** Too dark to see much: under-exposed or taken in very low light. */
    DARK("Too dark: add light or turn on the flash"),

    /** Over-exposed: washed out, with blown-out highlights. */
    BRIGHT("Too bright: avoid direct light behind or on the subject"),

    /** Glare, lens flare or a reflection washing out part of the photo (e.g. on paper, glass or a screen). */
    GLARE("Glare: tilt the camera or move away from the light"),

    /** Heavy grain or colour noise. */
    NOISE("Grainy: add light so the camera needs less boost"),

    /** A finger (or something close) covering part of the lens. */
    FINGER("Something covers the lens: move your finger away"),
}

/**
 * The result of [Retake.check]: a score from 0 to 1 for every [Issue], and the issues that reached the threshold.
 */
public class PhotoCheck internal constructor(
    /** A score per issue, 0..1. 0.5 is the default line between fine and a problem. */
    public val scores: Map<Issue, Float>,
    /** The line used for [issues]. */
    public val threshold: Float,
) {
    /** The problems found, most certain first. Empty for a good photo. */
    public val issues: List<Issue> = scores.filter { it.value >= threshold }.entries.sortedByDescending { it.value }.map { it.key }

    /** True when no issue reached the threshold: the photo can be kept. */
    public val isGood: Boolean get() = issues.isEmpty()

    /** The score of one issue, 0..1. */
    public fun score(issue: Issue): Float = scores.getValue(issue)

    /** The most likely problem and its score, even when it is below the threshold. */
    public val strongest: Pair<Issue, Float> get() = scores.maxBy { it.value }.toPair()

    override fun toString(): String =
        "PhotoCheck(${if (isGood) "good" else "retake: " + issues.joinToString()}, " +
            scores.entries.joinToString { "${it.key.name.lowercase()}=${(it.value * 1000).toInt() / 1000.0}" } + ")"
}
