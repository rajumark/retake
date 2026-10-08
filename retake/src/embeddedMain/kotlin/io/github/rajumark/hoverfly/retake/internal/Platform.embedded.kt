package io.github.rajumark.hoverfly.retake.internal

// Kotlin/Native and the web have no jar resources, so the model is compiled into the library
// as base64 text (generated from src/modelData by the generateEmbeddedModel task).
internal actual fun readModelFile(name: String): ByteArray =
    EmbeddedModel.files[name]?.let(::decodeChunks) ?: error("Retake model file $name is missing")
