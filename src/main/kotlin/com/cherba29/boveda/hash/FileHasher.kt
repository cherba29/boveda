package com.cherba29.boveda.hash

import com.dynatrace.hash4j.hashing.HashValues
import com.dynatrace.hash4j.hashing.Hashing
import java.io.File
import java.io.InputStream
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
   * Computes the hash of a file at [path] using the specified [algorithm].
   */
  fun computeHash(path: Path, algorithm: String = "XXH3"): String =
    computeHash(path.toFile(), algorithm)

  /**
   * Computes the hash of an [inputStream] using the specified [algorithm].
   */
  fun computeHash(inputStream: InputStream, algorithm: String = "XXH3"): String
}

/**
 * Standard implementation of [FileHasher] supporting high-performance non-cryptographic hashes
 * (such as XXH3, XXH3_128) as well as Java [MessageDigest] cryptographic algorithms (such as MD5, SHA-256).
 *
 * @property bufferSize The buffer size in bytes used when reading stream chunks (default 1MB).
 */
open class MessageDigestFileHasher(
  private val bufferSize: Int = 1024 * 1024
) : FileHasher {

  override fun computeHash(file: File, algorithm: String): String {
    require(file.exists()) { "File does not exist: ${file.absolutePath}" }
    require(
      file.isFile
    ) { "Cannot compute hash of a non-file or directory: ${file.absolutePath}" }

    return file.inputStream().use { inputStream ->
      computeHash(inputStream, algorithm)
    }
  }

  override fun computeHash(inputStream: InputStream, algorithm: String): String {
    val normalized = algorithm.uppercase().replace("-", "_")
    return when (normalized) {
      "XXH3", "XXH3_64", "XXH3_64BITS" -> {
        val stream = Hashing.xxh3_64().hashStream()
        val buffer = ByteArray(bufferSize)
        var bytesRead = inputStream.read(buffer)
        while (bytesRead != -1) {
          stream.putBytes(buffer, 0, bytesRead)
          bytesRead = inputStream.read(buffer)
        }
        HashValues.toHexString(stream.asLong)
      }
      "XXH3_128", "XXH128", "XXH3_128BITS" -> {
        val stream = Hashing.xxh3_128().hashStream()
        val buffer = ByteArray(bufferSize)
        var bytesRead = inputStream.read(buffer)
        while (bytesRead != -1) {
          stream.putBytes(buffer, 0, bytesRead)
          bytesRead = inputStream.read(buffer)
        }
        HashValues.toHexString(stream.get())
      }
      "XXH64" -> {
        val stream = Hashing.xxh64().hashStream()
        val buffer = ByteArray(bufferSize)
        var bytesRead = inputStream.read(buffer)
        while (bytesRead != -1) {
          stream.putBytes(buffer, 0, bytesRead)
          bytesRead = inputStream.read(buffer)
        }
        HashValues.toHexString(stream.asLong)
      }
      "XXH32" -> {
        val stream = Hashing.xxh32().hashStream()
        val buffer = ByteArray(bufferSize)
        var bytesRead = inputStream.read(buffer)
        while (bytesRead != -1) {
          stream.putBytes(buffer, 0, bytesRead)
          bytesRead = inputStream.read(buffer)
        }
        HashValues.toHexString(stream.asInt)
      }
      else -> {
        val messageDigest = MessageDigest.getInstance(algorithm)
        val buffer = ByteArray(bufferSize)
        var bytesRead = inputStream.read(buffer)
        while (bytesRead != -1) {
          messageDigest.update(buffer, 0, bytesRead)
          bytesRead = inputStream.read(buffer)
        }
        @OptIn(ExperimentalStdlibApi::class)
        messageDigest.digest().toHexString()
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
fun computeFileHash(file: File, algorithm: String = "XXH3"): String =
  MessageDigestFileHasher().computeHash(file, algorithm)
