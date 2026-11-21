package com.cherba29.boveda.hash

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FileHasherTest {

  private val hasher = MessageDigestFileHasher()

  @Test
  fun `computeHash defaults to XXH3 and produces correct hash for empty content`() {
    val tempFile = Files.createTempFile("hasher-test-default-empty", ".txt").toFile()
    try {
      val hash = hasher.computeHash(tempFile)
      assertEquals("2d06800538d394c2", hash)
    } finally {
      tempFile.delete()
    }
  }

  @Test
  fun `computeHash returns correct XXH3 for empty input stream`() {
    val stream = ByteArrayInputStream(ByteArray(0))
    val hash = hasher.computeHash(stream, "XXH3")
    assertEquals("2d06800538d394c2", hash)
  }

  @Test
  fun `computeHash returns correct XXH3_128 for empty file`() {
    val tempFile = Files.createTempFile("hasher-test-empty-128", ".txt").toFile()
    try {
      val hash = hasher.computeHash(tempFile, "XXH3_128")
      assertEquals("99aa06d3014798d86001c324468d497f", hash)
    } finally {
      tempFile.delete()
    }
  }

  @Test
  fun `computeHash returns correct XXH3 for known text`() {
    val tempFile = Files.createTempFile("hasher-test-xxh3", ".txt").toFile()
    try {
      tempFile.writeText("hello world")
      val hashDefault = hasher.computeHash(tempFile)
      val hashExplicit = hasher.computeHash(tempFile, "XXH3")
      val hashCase = hasher.computeHash(tempFile, "xxh3_64")
      val hashDash = hasher.computeHash(tempFile, "xxh3-64")
      assertEquals(hashDefault, hashExplicit)
      assertEquals(hashDefault, hashCase)
      assertEquals(hashDefault, hashDash)
      assertEquals(16, hashDefault.length)
    } finally {
      tempFile.delete()
    }
  }

  @Test
  fun `computeHash returns correct CRC32 for known text and empty input`() {
    val tempFile = Files.createTempFile("hasher-test-crc32", ".txt").toFile()
    try {
      tempFile.writeText("hello world")
      val hash = hasher.computeHash(tempFile, "CRC32")
      assertEquals("0d4a1185", hash)

      val emptyStream = ByteArrayInputStream(ByteArray(0))
      val emptyHash = hasher.computeHash(emptyStream, "CRC32")
      assertEquals("00000000", emptyHash)
    } finally {
      tempFile.delete()
    }
  }

  @Test
  fun `computeHash returns correct MD5 for known text`() {
    val tempFile = Files.createTempFile("hasher-test", ".txt").toFile()
    try {
      tempFile.writeText("hello world")
      // Known MD5 of "hello world" is 5eb63bbbe01eeed093cb22bb8f5acdc3
      val hash = hasher.computeHash(tempFile, "MD5")
      assertEquals("5eb63bbbe01eeed093cb22bb8f5acdc3", hash)
    } finally {
      tempFile.delete()
    }
  }

  @Test
  fun `computeHash returns correct SHA-256 for known text`() {
    val tempFile = Files.createTempFile("hasher-test-sha256", ".txt").toFile()
    try {
      tempFile.writeText("hello world")
      // Known SHA-256 of "hello world" is b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9
      val hash = hasher.computeHash(tempFile, "SHA-256")
      assertEquals("b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9", hash)
    } finally {
      tempFile.delete()
    }
  }

  @Test
  fun `computeHash handles empty file correctly`() {
    val tempFile = Files.createTempFile("hasher-test-empty", ".txt").toFile()
    try {
      // Known MD5 of empty content is d41d8cd98f00b204e9800998ecf8427e
      val hash = hasher.computeHash(tempFile, "MD5")
      assertEquals("d41d8cd98f00b204e9800998ecf8427e", hash)
    } finally {
      tempFile.delete()
    }
  }

  @Test
  fun `computeHash with Path overload works identically`() {
    val tempFile = Files.createTempFile("hasher-test-path", ".txt")
    try {
      Files.writeString(tempFile, "sample data")
      val hashViaFile = hasher.computeHash(tempFile.toFile())
      val hashViaPath = hasher.computeHash(tempFile)
      assertEquals(hashViaFile, hashViaPath)
    } finally {
      Files.deleteIfExists(tempFile)
    }
  }

  @Test
  fun `computeHash from InputStream computes expected digest`() {
    val stream = ByteArrayInputStream("hello world".toByteArray())
    val hash = hasher.computeHash(stream, "MD5")
    assertEquals("5eb63bbbe01eeed093cb22bb8f5acdc3", hash)
  }

  @Test
  fun `computeHash throws IllegalArgumentException on non-existent file`() {
    val nonExistent = File("/path/to/definitely/non/existent/file.boveda")
    val exception = assertFailsWith<IllegalArgumentException> {
      hasher.computeHash(nonExistent)
    }
    assertTrue(exception.message!!.contains("File does not exist"))
  }

  @Test
  fun `computeHash throws IllegalArgumentException when target is a directory`() {
    val tempDir = Files.createTempDirectory("hasher-test-dir").toFile()
    try {
      val exception = assertFailsWith<IllegalArgumentException> {
        hasher.computeHash(tempDir)
      }
      assertTrue(
        exception.message!!.contains("Cannot compute hash of a non-file or directory")
      )
    } finally {
      tempDir.delete()
    }
  }

  @Test
  fun `top-level computeFileHash function works for backward compatibility`() {
    val tempFile = Files.createTempFile("hasher-compat-test", ".txt").toFile()
    try {
      tempFile.writeText("hello world")
      val hash = computeFileHash(tempFile, "MD5")
      assertEquals("5eb63bbbe01eeed093cb22bb8f5acdc3", hash)
    } finally {
      tempFile.delete()
    }
  }

  @Test
  fun `parseByteSize parses standard human readable and numeric byte formats`() {
    assertEquals(null, parseByteSize(null))
    assertEquals(null, parseByteSize(""))
    assertEquals(null, parseByteSize("full"))
    assertEquals(null, parseByteSize("full size"))
    assertEquals(null, parseByteSize("all"))
    assertEquals(null, parseByteSize("max"))

    assertEquals(65536L, parseByteSize("64Kb"))
    assertEquals(65536L, parseByteSize("64KB"))
    assertEquals(65536L, parseByteSize("64kb"))
    assertEquals(65536L, parseByteSize("64k"))

    assertEquals(131072L, parseByteSize("128Kb"))
    assertEquals(262144L, parseByteSize("256Kb"))
    assertEquals(524288L, parseByteSize("512Kb"))
    assertEquals(1048576L, parseByteSize("1Mb"))
    assertEquals(1048576L, parseByteSize("1MB"))
    assertEquals(1048576L, parseByteSize("1M"))

    assertEquals(1024L, parseByteSize("1024"))
    assertEquals(1024L, parseByteSize("1024 bytes"))
    assertEquals(1024L, parseByteSize("1024b"))

    assertFailsWith<IllegalArgumentException> { parseByteSize("abc") }
    assertFailsWith<IllegalArgumentException> { parseByteSize("0") }
    assertFailsWith<IllegalArgumentException> { parseByteSize("-10kb") }
  }

  @Test
  fun `computeHash with maxBytes only hashes up to prefix and incorporates file size`() {
    val dir = Files.createTempDirectory("hasher-prefix-test")
    try {
      val prefix = "X".repeat(1024)

      // fileA: 2048 bytes (prefix + "A" * 1024)
      val fileA = dir.resolve("fileA.bin").toFile()
      fileA.writeText(prefix + "A".repeat(1024))

      // fileB: 2048 bytes (prefix + "B" * 1024) - same size as fileA, same 1024 prefix, different trailing bytes
      val fileB = dir.resolve("fileB.bin").toFile()
      fileB.writeText(prefix + "B".repeat(1024))

      // fileC: 4096 bytes (prefix + "A" * 3072) - different size, same 1024 prefix
      val fileC = dir.resolve("fileC.bin").toFile()
      fileC.writeText(prefix + "A".repeat(3072))

      // fileD: 2048 bytes ("Y" * 1024 + "A" * 1024) - same size as fileA, different prefix
      val fileD = dir.resolve("fileD.bin").toFile()
      fileD.writeText("Y".repeat(1024) + "A".repeat(1024))

      val limit = 1024L

      // 1. Same size and same prefix up to limit -> same hash!
      val hashA = hasher.computeHash(fileA, "XXH3", maxBytes = limit)
      val hashB = hasher.computeHash(fileB, "XXH3", maxBytes = limit)
      assertEquals(hashA, hashB)

      // 2. Different size with same prefix -> different hash!
      val hashC = hasher.computeHash(fileC, "XXH3", maxBytes = limit)
      assertTrue(hashA != hashC)

      // 3. Same size with different prefix -> different hash!
      val hashD = hasher.computeHash(fileD, "XXH3", maxBytes = limit)
      assertTrue(hashA != hashD)

      // 4. Full size without maxBytes -> fileA and fileB have different hashes
      val fullHashA = hasher.computeHash(fileA, "XXH3", maxBytes = null)
      val fullHashB = hasher.computeHash(fileB, "XXH3", maxBytes = null)
      assertTrue(fullHashA != fullHashB)

      // 5. Works identically with MD5 and CRC32
      val md5A = hasher.computeHash(fileA, "MD5", maxBytes = limit)
      val md5B = hasher.computeHash(fileB, "MD5", maxBytes = limit)
      assertEquals(md5A, md5B)

      val md5C = hasher.computeHash(fileC, "MD5", maxBytes = limit)
      assertTrue(md5A != md5C)

      val crcA = hasher.computeHash(fileA, "CRC32", maxBytes = limit)
      val crcB = hasher.computeHash(fileB, "CRC32", maxBytes = limit)
      assertEquals(crcA, crcB)

      val crcC = hasher.computeHash(fileC, "CRC32", maxBytes = limit)
      assertTrue(crcA != crcC)
    } finally {
      dir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `MessageDigestFileHasher validates bufferSize and safely reuses thread-local buffers`() {
    assertFailsWith<IllegalArgumentException> {
      MessageDigestFileHasher(bufferSize = 0)
    }

    val smallBufHasher = MessageDigestFileHasher(bufferSize = 64)
    val executor = java.util.concurrent.Executors.newFixedThreadPool(4)
    try {
      val tasks = (1..20).map { i ->
        java.util.concurrent.Callable {
          val payload = "thread-safe-payload-$i-".repeat(20).toByteArray()
          val expected = hasher.computeHash(ByteArrayInputStream(payload), "SHA-256")
          val actual = smallBufHasher.computeHash(ByteArrayInputStream(payload), "SHA-256")
          expected to actual
        }
      }
      for (future in executor.invokeAll(tasks)) {
        val (expected, actual) = future.get()
        assertEquals(expected, actual)
      }
    } finally {
      executor.shutdown()
    }
  }

  @Test
  fun `computeHash with knownFileSize matches File overload and short-circuits zero-byte files`() {
    val tempFile = Files.createTempFile("hasher-known-size", ".bin")
    val emptyFile = Files.createTempFile("hasher-empty-known", ".bin")
    try {
      val content = "fast-path-known-size-payload-".repeat(200).toByteArray()
      Files.write(tempFile, content)

      val algorithms = listOf("XXH3", "XXH3_128", "CRC32", "MD5", "SHA-256")
      for (algo in algorithms) {
        assertEquals(
          hasher.computeHash(tempFile.toFile(), algo, maxBytes = null),
          hasher.computeHash(tempFile, algo, maxBytes = null, knownFileSize = content.size.toLong())
        )
        assertEquals(
          hasher.computeHash(tempFile.toFile(), algo, maxBytes = 1024L),
          hasher.computeHash(
            tempFile,
            algo,
            maxBytes = 1024L,
            knownFileSize = content.size.toLong()
          )
        )
        // Zero-byte short-circuit matches empty file identically without opening disk handle
        val nonExistent = tempFile.parent.resolve("never-opened-zero-byte.bin")
        assertEquals(
          hasher.computeHash(emptyFile.toFile(), algo, maxBytes = null),
          hasher.computeHash(nonExistent, algo, maxBytes = null, knownFileSize = 0L)
        )
        assertEquals(
          hasher.computeHash(emptyFile.toFile(), algo, maxBytes = 1024L),
          hasher.computeHash(nonExistent, algo, maxBytes = 1024L, knownFileSize = 0L)
        )
      }
    } finally {
      Files.deleteIfExists(tempFile)
      Files.deleteIfExists(emptyFile)
    }
  }
}
