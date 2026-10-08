package io.github.rajumark.hoverfly.retake.internal

import kotlin.io.encoding.Base64

/** Decodes a file split into base64 chunks (each a multiple of 4 characters) by the build. */
internal fun decodeChunks(chunks: Array<String>): ByteArray {
    val parts = chunks.map { Base64.decode(it) }
    val out = ByteArray(parts.sumOf { it.size })
    var o = 0
    for (p in parts) { p.copyInto(out, o); o += p.size }
    return out
}
