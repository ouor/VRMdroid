package com.ouor.vrmdroid.avatar

import java.io.DataInputStream
import java.io.File
import java.io.InputStream

/**
 * Cheap check that a file is a VRM before it replaces the current avatar: a binary glTF whose
 * JSON chunk declares the VRM 0.x (`VRM`) or VRM 1.0 (`VRMC_vrm`) extension. Catches renamed or
 * plain glTF files without parsing the model.
 */
object VrmCheck {
    private const val GLTF_MAGIC = 0x46546C67 // "glTF", little endian
    private const val JSON_CHUNK = 0x4E4F534A // "JSON"
    private const val MAX_JSON_BYTES = 16 shl 20
    private val VRM_EXTENSION = Regex(""""(VRM|VRMC_vrm)"\s*:\s*\{""")

    fun isVrm(file: File): Boolean = runCatching { file.inputStream().buffered().use(::isVrm) }.getOrDefault(false)

    fun isVrm(input: InputStream): Boolean {
        val data = DataInputStream(input)
        if (data.readIntLe() != GLTF_MAGIC) return false
        data.readIntLe() // version
        data.readIntLe() // total length
        val jsonLength = data.readIntLe()
        if (data.readIntLe() != JSON_CHUNK || jsonLength !in 1..MAX_JSON_BYTES) return false
        val json = ByteArray(jsonLength).also { data.readFully(it) }.toString(Charsets.UTF_8)
        // The extension object itself; some exporters leave "extensionsUsed" out.
        return VRM_EXTENSION.containsMatchIn(json)
    }

    private fun DataInputStream.readIntLe(): Int = Integer.reverseBytes(readInt())
}
