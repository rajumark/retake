package io.github.rajumark.hoverfly.retake.internal

import io.github.rajumark.hoverfly.retake.Retake

// The model ships as Java resources in the jar/AAR (src/modelData), so no Context or copy is needed.
internal actual fun readModelFile(name: String): ByteArray =
    Retake::class.java.getResourceAsStream("/io/github/rajumark/hoverfly/retake/model/$name")?.use { it.readBytes() }
        ?: error("Retake model file $name is missing from the library jar")
