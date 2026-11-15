package com.cherba29.boveda.hash

import java.io.File
import java.io.InputStream
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Interface for computing cryptographic hashes of files and input streams.
 */
interface FileHasher {
  /**
   * Computes the hash of a [file] using the specified [algorithm].
   */
  fun computeHash(file: File, algorithm: String = "MD5"): String

  /**
   * Computes the hash of a file at [path] using the specified [algorithm].
   */
  fun computeHash(path: Path, algorithm: String = "MD5"): String =
    computeHash(path.toFile(), algorithm)

  /**
   * Computes the hash of an [inputStream] using the specified [algorithm].
   */
  fun computeHash(inputStream: InputStream, algorithm: String = "MD5"): String
}

/**
 * Standard implementation of [FileHasher] using Java's [MessageDigest].
 *
 * @property bufferSize The buffer size in bytes used when reading stream chunks (default 1MB).
 */
class MessageDigestFileHasher(
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
    val messageDigest = MessageDigest.getInstance(algorithm)
    val buffer = ByteArray(bufferSize)
    var bytesRead = inputStream.read(buffer)
    while (bytesRead != -1) {
      messageDigest.update(buffer, 0, bytesRead)
      bytesRead = inputStream.read(buffer)
    }
    return messageDigest.digest().toHexString()
  }
}

/**
 * Backwards-compatible convenience function to compute file hashes using [MessageDigestFileHasher].
 */
fun computeFileHash(file: File, algorithm: String = "MD5"): String =
  MessageDigestFileHasher().computeHash(file, algorithm)
