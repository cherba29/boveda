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
}
