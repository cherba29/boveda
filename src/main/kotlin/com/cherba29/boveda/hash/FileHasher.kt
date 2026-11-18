package com.cherba29.boveda.hash

import com.dynatrace.hash4j.hashing.HashValues
import com.dynatrace.hash4j.hashing.Hashing
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Interface for computing hashes of files and input streams.
 */
interface FileHasher {
  /**
   * Computes the hash of a [file] using the specified [algorithm].
   */
  fun computeHash(file: File, algorithm: String = "XXH3"): String

  /**
   * Computes the hash of a [file] using the specified [algorithm], reading at most [maxBytes].
   * If [maxBytes] is specified, the file size is included in the hash computation.
   */
  fun computeHash(file: File, algorithm: String = "XXH3", maxBytes: Long? = null): String =
    if (maxBytes == null) {
      computeHash(file, algorithm)
    } else {
      computeHash(file, algorithm)
    }

  /**
   * Computes the hash of a file at [path] using the specified [algorithm].
   */
  fun computeHash(path: Path, algorithm: String = "XXH3"): String =
    computeHash(path.toFile(), algorithm)

  /**
   * Computes the hash of a file at [path] using the specified [algorithm], reading at most [maxBytes].
   */
  fun computeHash(path: Path, algorithm: String = "XXH3", maxBytes: Long? = null): String =
    computeHash(path.toFile(), algorithm, maxBytes)

  /**
   * Computes the hash of an [inputStream] using the specified [algorithm].
   */
  fun computeHash(inputStream: InputStream, algorithm: String = "XXH3"): String

  /**
   * Computes the hash of an [inputStream] using the specified [algorithm], reading at most [maxBytes].
   * If [fileSize] is provided and [maxBytes] is not null, the file size is incorporated into the hash.
   */
  fun computeHash(
    inputStream: InputStream,
    algorithm: String = "XXH3",
    maxBytes: Long? = null,
    fileSize: Long? = null
  ): String = if (maxBytes == null && fileSize == null) {
    computeHash(inputStream, algorithm)
  } else {
    computeHash(inputStream, algorithm)
  }
}

/**
 * Parses a human-readable byte size string (e.g. "64Kb", "128Kb", "256Kb", "512Kb", "1Mb", "full size", "65536").
 * Returns `null` if the value represents full file size or is null/empty.
 */
fun parseByteSize(value: String?): Long? {
  if (value == null) return null
  val trimmed = value.trim().lowercase().replace(" ", "").replace("_", "")
  val isFull = trimmed.isEmpty() ||
    trimmed == "full" ||
    trimmed == "fullsize" ||
    trimmed == "full-size" ||
    trimmed == "all" ||
    trimmed == "max"
  if (isFull) {
    return null
  }

  val regex = Regex("""^(\d+(?:\.\d+)?)\s*([a-z]*)$""")
  val match = regex.matchEntire(trimmed)
    ?: throw IllegalArgumentException("Invalid byte size format: '$value'")

  val numberPart = match.groupValues[1].toDoubleOrNull()
    ?: throw IllegalArgumentException("Invalid number in byte size: '$value'")
  val unitPart = match.groupValues[2]

  val multiplier: Long = when (unitPart) {
    "", "b", "bytes" -> 1L
    "k", "kb", "kib" -> 1024L
    "m", "mb", "mib" -> 1024L * 1024L
    "g", "gb", "gib" -> 1024L * 1024L * 1024L
    "t", "tb", "tib" -> 1024L * 1024L * 1024L * 1024L
    else -> throw IllegalArgumentException("Unknown byte size unit: '$unitPart' in '$value'")
  }

  val bytes = (numberPart * multiplier).toLong()
  require(bytes > 0) { "Byte size must be positive: '$value'" }
  return bytes
}

/**
 * Standard implementation of [FileHasher] supporting high-performance non-cryptographic hashes
 * (such as XXH3, XXH3_128, CRC32, CRC32C) as well as Java [MessageDigest] cryptographic algorithms (such as MD5, SHA-256).
 *
 * @property bufferSize The buffer size in bytes used when reading stream chunks (default 1MB).
 */
open class MessageDigestFileHasher(
  private val bufferSize: Int = 1024 * 1024
) : FileHasher {

  override fun computeHash(file: File, algorithm: String): String =
    computeHash(file, algorithm, maxBytes = null)

  override fun computeHash(file: File, algorithm: String, maxBytes: Long?): String {
    require(file.exists()) { "File does not exist: ${file.absolutePath}" }
    require(
      file.isFile
    ) { "Cannot compute hash of a non-file or directory: ${file.absolutePath}" }

    return file.inputStream().use { inputStream ->
      computeHash(
        inputStream = inputStream,
        algorithm = algorithm,
        maxBytes = maxBytes,
        fileSize = file.length()
      )
    }
  }

  override fun computeHash(inputStream: InputStream, algorithm: String): String =
    computeHash(inputStream, algorithm, maxBytes = null, fileSize = null)

  override fun computeHash(
    inputStream: InputStream,
    algorithm: String,
    maxBytes: Long?,
    fileSize: Long?
  ): String {
    val normalized = algorithm.uppercase().replace("-", "_")
    return when (normalized) {
      "XXH3", "XXH3_64", "XXH3_64BITS" -> {
        val stream = Hashing.xxh3_64().hashStream()
        readStream(inputStream, maxBytes) { buffer, bytesRead ->
          stream.putBytes(buffer, 0, bytesRead)
        }
        if (maxBytes != null && fileSize != null) {
          stream.putLong(fileSize)
        }
        HashValues.toHexString(stream.asLong)
      }
      "XXH3_128", "XXH128", "XXH3_128BITS" -> {
        val stream = Hashing.xxh3_128().hashStream()
        readStream(inputStream, maxBytes) { buffer, bytesRead ->
          stream.putBytes(buffer, 0, bytesRead)
        }
        if (maxBytes != null && fileSize != null) {
          stream.putLong(fileSize)
        }
        HashValues.toHexString(stream.get())
      }
      "XXH64" -> {
        val stream = Hashing.xxh64().hashStream()
        readStream(inputStream, maxBytes) { buffer, bytesRead ->
          stream.putBytes(buffer, 0, bytesRead)
        }
        if (maxBytes != null && fileSize != null) {
          stream.putLong(fileSize)
        }
        HashValues.toHexString(stream.asLong)
      }
      "XXH32" -> {
        val stream = Hashing.xxh32().hashStream()
        readStream(inputStream, maxBytes) { buffer, bytesRead ->
          stream.putBytes(buffer, 0, bytesRead)
        }
        if (maxBytes != null && fileSize != null) {
          stream.putLong(fileSize)
        }
        HashValues.toHexString(stream.asInt)
      }
      "CRC32" -> {
        val crc = java.util.zip.CRC32()
        readStream(inputStream, maxBytes) { buffer, bytesRead ->
          crc.update(buffer, 0, bytesRead)
        }
        if (maxBytes != null && fileSize != null) {
          val sizeBytes = ByteBuffer.allocate(java.lang.Long.BYTES).putLong(fileSize).array()
          crc.update(sizeBytes)
        }
        "%08x".format(crc.value)
      }
      "CRC32C" -> {
        val crc = java.util.zip.CRC32C()
        readStream(inputStream, maxBytes) { buffer, bytesRead ->
          crc.update(buffer, 0, bytesRead)
        }
        if (maxBytes != null && fileSize != null) {
          val sizeBytes = ByteBuffer.allocate(java.lang.Long.BYTES).putLong(fileSize).array()
          crc.update(sizeBytes)
        }
        "%08x".format(crc.value)
      }
      else -> {
        val messageDigest = MessageDigest.getInstance(algorithm)
        readStream(inputStream, maxBytes) { buffer, bytesRead ->
          messageDigest.update(buffer, 0, bytesRead)
        }
        if (maxBytes != null && fileSize != null) {
          val sizeBytes = ByteBuffer.allocate(java.lang.Long.BYTES).putLong(fileSize).array()
          messageDigest.update(sizeBytes)
        }
        @OptIn(ExperimentalStdlibApi::class)
        messageDigest.digest().toHexString()
      }
    }
  }

  private inline fun readStream(
    inputStream: InputStream,
    maxBytes: Long?,
    onChunk: (buffer: ByteArray, len: Int) -> Unit
  ) {
    var remaining = maxBytes
    val bufSize = if (remaining != null) {
      minOf(bufferSize.toLong(), remaining).toInt().coerceAtLeast(1)
    } else {
      bufferSize
    }
    val buffer = ByteArray(bufSize)
    while (remaining == null || remaining > 0) {
      val toRead = if (remaining != null) {
        minOf(buffer.size.toLong(), remaining).toInt()
      } else {
        buffer.size
      }
      val bytesRead = inputStream.read(buffer, 0, toRead)
      if (bytesRead == -1) break
      onChunk(buffer, bytesRead)
      if (remaining != null) {
        remaining -= bytesRead
      }
    }
  }
}

/**
 * Typealias for [MessageDigestFileHasher] representing the default file hasher implementation.
 */
typealias DefaultFileHasher = MessageDigestFileHasher

/**
 * Backwards-compatible convenience function to compute file hashes using [MessageDigestFileHasher].
 */
fun computeFileHash(file: File, algorithm: String = "XXH3", maxBytes: Long? = null): String =
  MessageDigestFileHasher().computeHash(file, algorithm, maxBytes)
