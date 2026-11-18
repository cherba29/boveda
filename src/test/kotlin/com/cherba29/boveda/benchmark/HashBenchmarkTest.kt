package com.cherba29.boveda.benchmark

import com.cherba29.boveda.cmds.ScanCommand
import com.cherba29.boveda.hash.MessageDigestFileHasher
import com.cherba29.boveda.hash.parseByteSize
import com.github.ajalt.clikt.testing.test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

@EnabledIfSystemProperty(named = "benchmark", matches = "true")
class HashBenchmarkTest {

  private val hasher = MessageDigestFileHasher()

  data class BenchmarkResult(
    val algorithm: String,
    val fileSizeMb: Int,
    val maxHashBytesLabel: String,
    val maxHashBytes: Long?,
    val avgDurationMs: Double,
    val minDurationMs: Double,
    val throughputMbPerSec: Double,
    val hashOutput: String
  )

  @Test
  fun `run comprehensive hashing benchmarks`() {
    // Only run full benchmark when enabled via property or env, or run a fast sample
    val isFullBenchmark = System.getProperty("benchmark.full") == "true" ||
      System.getenv("BENCHMARK_FULL") == "true"

    println("=== STARTING HASHING BENCHMARK (full=$isFullBenchmark) ===")

    val tempDir = Files.createTempDirectory("boveda-hash-benchmark")
    try {
      // 1. Raw CPU In-Memory Throughput (isolating algorithm speed without disk I/O)
      println("\n--- Phase 1: In-Memory Algorithmic Throughput (64 MB buffer) ---")
      val rawResults = benchmarkInMemoryThroughput(bufferSizeMb = 64)
      rawResults.forEach { (algo, gbPerSec) ->
        println(String.format("Algorithm: %-10s -> Throughput: %.2f GB/s", algo, gbPerSec))
      }

      // 2. Large File Benchmarks across algorithms and max-hash-bytes
      val fileSizesMb = listOf(100, 500, 1000)
      val maxBytesOptions = listOf(
        "full" to null,
        "10Mb" to parseByteSize("10Mb"),
        "1Mb" to parseByteSize("1Mb"),
        "512Kb" to parseByteSize("512Kb"),
        "256Kb" to parseByteSize("256Kb"),
        "64Kb" to parseByteSize("64Kb")
      )
      val algorithms = listOf("XXH3", "CRC32", "MD5")

      val results = mutableListOf<BenchmarkResult>()

      for (fileSizeMb in fileSizesMb) {
        println("\nGenerating $fileSizeMb MB test file...")
        val testFile = tempDir.resolve("test_file_${fileSizeMb}MB.bin").toFile()
        createPseudoRandomFile(testFile, fileSizeMb.toLong() * 1024 * 1024)

        // Warm up file in page cache
        testFile.inputStream().use { stream ->
          val buf = ByteArray(1024 * 1024)
          while (stream.read(buf) != -1) { /* no-op */ }
        }

        println("Benchmarking $fileSizeMb MB file across algorithms and --max-hash-bytes settings:")
        for ((label, maxBytes) in maxBytesOptions) {
          for (algo in algorithms) {
            // Warmup iterations
            repeat(2) {
              hasher.computeHash(testFile, algo, maxBytes)
            }

            // Timed iterations
            val iterations = if (fileSizeMb >= 1000 && maxBytes == null) 3 else 5
            val timingsMs = mutableListOf<Double>()
            var lastHash = ""

            for (i in 1..iterations) {
              val start = System.nanoTime()
              lastHash = hasher.computeHash(testFile, algo, maxBytes)
              val elapsedMs = (System.nanoTime() - start) / 1_000_000.0
              timingsMs.add(elapsedMs)
            }

            val avgMs = timingsMs.average()
            val minMs = timingsMs.minOrNull() ?: avgMs
            val throughputMbSec = (fileSizeMb.toDouble() / (avgMs / 1000.0))

            val result = BenchmarkResult(
              algorithm = algo,
              fileSizeMb = fileSizeMb,
              maxHashBytesLabel = label,
              maxHashBytes = maxBytes,
              avgDurationMs = avgMs,
              minDurationMs = minMs,
              throughputMbPerSec = throughputMbSec,
              hashOutput = lastHash
            )
            results.add(result)

            println(
              String.format(
                "  Size: %4d MB | Algo: %-5s | Limit: %-6s | Avg: %8.2f ms | " +
                  "Min: %8.2f ms | Rate: %9.2f MB/s | Hash: %s",
                fileSizeMb,
                algo,
                label,
                avgMs,
                minMs,
                throughputMbSec,
                lastHash.take(12) + "..."
              )
            )
          }
        }
      }

      // 3. End-to-end CLI Scan Benchmark
      println("\n--- Phase 3: CLI Scan Command Benchmark on Directory of Large Files ---")
      val cliDir = tempDir.resolve("cli_scan_dir").apply { Files.createDirectories(this) }
      // Create 3 large files of 50MB each (2 identical, 1 unique)
      val f1 = cliDir.resolve("large1.bin").toFile()
      val f2 = cliDir.resolve("large2.bin").toFile()
      val f3 = cliDir.resolve("large3.bin").toFile()
      createPseudoRandomFile(f1, 50L * 1024 * 1024, seed = 12345)
      f1.copyTo(f2, overwrite = true)
      createPseudoRandomFile(f3, 50L * 1024 * 1024, seed = 67890)

      val cliConfigs = listOf(
        Triple("XXH3", "full", null),
        Triple("XXH3", "1Mb", "1Mb"),
        Triple("XXH3", "64Kb", "64Kb"),
        Triple("CRC32", "full", null),
        Triple("CRC32", "1Mb", "1Mb"),
        Triple("CRC32", "64Kb", "64Kb"),
        Triple("MD5", "full", null),
        Triple("MD5", "1Mb", "1Mb"),
        Triple("MD5", "64Kb", "64Kb")
      )

      val cliResults = mutableListOf<Triple<String, String, Double>>()

      // Warmup H2 and Exposed
      val warmupDb = tempDir.resolve("warmup.h2")
      ScanCommand().test(listOf("--quiet", cliDir.toString(), warmupDb.toString()))

      for ((algo, label, maxOpt) in cliConfigs) {
        val dbFile = tempDir.resolve("test_db_${algo}_$label.h2")
        val args = mutableListOf(
          "--quiet",
          "--algorithm",
          algo,
          cliDir.toString(),
          dbFile.toString()
        )
        if (maxOpt != null) {
          args.add("--max-hash-bytes")
          args.add(maxOpt)
        }

        val scanCmd = ScanCommand()
        val start = System.nanoTime()
        val res = scanCmd.test(args)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000.0
        assertTrue(res.statusCode == 0, "Scan command failed for $algo $label: ${res.output}")
        cliResults.add(Triple(algo, label, elapsedMs))
        println(
          String.format(
            "  CLI Scan: Algo: %-5s | Limit: %-6s | Elapsed: %8.2f ms",
            algo,
            label,
            elapsedMs
          )
        )
      }

      // Write results to persistent file in scratch directory
      writeMarkdownReport(rawResults, results, cliResults)
    } finally {
      tempDir.toFile().deleteRecursively()
    }
  }

  private fun benchmarkInMemoryThroughput(bufferSizeMb: Int): Map<String, Double> {
    val sizeBytes = bufferSizeMb * 1024 * 1024
    val data = ByteArray(sizeBytes)
    Random(42).nextBytes(data)

    val algorithms = listOf("XXH3", "CRC32", "CRC32C", "MD5")
    val resultMap = mutableMapOf<String, Double>()

    for (algo in algorithms) {
      // Warmup
      repeat(3) {
        hasher.computeHash(ByteArrayInputStream(data), algo)
      }

      val iterations = 10
      val timingsNs = mutableListOf<Long>()
      for (i in 1..iterations) {
        val stream = ByteArrayInputStream(data)
        val start = System.nanoTime()
        hasher.computeHash(stream, algo)
        val elapsed = System.nanoTime() - start
        timingsNs.add(elapsed)
      }

      val avgNs = timingsNs.average()
      val avgSec = avgNs / 1_000_000_000.0
      val gbPerSec = (bufferSizeMb.toDouble() / 1024.0) / avgSec
      resultMap[algo] = gbPerSec
    }
    return resultMap
  }

  private fun createPseudoRandomFile(file: File, sizeBytes: Long, seed: Long = 42L) {
    val chunkSize = 1024 * 1024 // 1MB chunk
    val buffer = ByteArray(chunkSize)
    val random = Random(seed)
    random.nextBytes(buffer)

    RandomAccessFile(file, "rw").use { raf ->
      var written = 0L
      while (written < sizeBytes) {
        val toWrite = minOf(chunkSize.toLong(), sizeBytes - written).toInt()
        raf.write(buffer, 0, toWrite)
        written += toWrite
      }
    }
  }

  private fun writeMarkdownReport(
    rawResults: Map<String, Double>,
    results: List<BenchmarkResult>,
    cliResults: List<Triple<String, String, Double>>
  ) {
    val reportDir = File(
      "/home/arti/.gemini/antigravity-cli/brain/5ef706ef-bfd5-4f37-926d-9fb5ed196fc7/scratch"
    )
    if (!reportDir.exists()) {
      reportDir.mkdirs()
    }
    val tsvFile = File(reportDir, "benchmark_data.tsv")
    tsvFile.printWriter().use { out ->
      out.println("algo\tfileSizeMb\tlimit\tavgMs\tminMs\tthroughputMbSec\thash")
      for (r in results) {
        out.println(
          "${r.algorithm}\t${r.fileSizeMb}\t${r.maxHashBytesLabel}\t" +
            "${r.avgDurationMs}\t${r.minDurationMs}\t${r.throughputMbPerSec}\t${r.hashOutput}"
        )
      }
    }
    println("Benchmark TSV written to: ${tsvFile.absolutePath}")

    val mdFile = File(reportDir, "benchmark_summary.md")
    mdFile.printWriter().use { out ->
      out.println("# Hashing Benchmark Summary: XXH3 vs CRC32 vs MD5")
      out.println()
      out.println("## 1. Pure CPU Algorithm Throughput (In-Memory 64 MB buffer)")
      out.println("| Algorithm | Throughput (GB/s) | Relative to MD5 |")
      out.println("| :--- | :--- | :--- |")
      val md5Speed = rawResults["MD5"] ?: 1.0
      for ((algo, speed) in rawResults) {
        val rel = speed / md5Speed
        out.println(String.format("| `%s` | %.2f GB/s | %.1fx |", algo, speed, rel))
      }
      out.println()
      out.println("## 2. File Hashing Latency and Throughput by File Size & Max-Hash-Bytes")
      out.println(
        "| File Size | Algorithm | `--max-hash-bytes` | Latency (avg) | " +
          "Latency (min) | Effective Rate |"
      )
      out.println("| :--- | :--- | :--- | :--- | :--- | :--- |")
      for (r in results) {
        val latStr = if (r.avgDurationMs < 1.0) {
          String.format("%.2f µs", r.avgDurationMs * 1000.0)
        } else {
          String.format("%.2f ms", r.avgDurationMs)
        }
        val minStr = if (r.minDurationMs < 1.0) {
          String.format("%.2f µs", r.minDurationMs * 1000.0)
        } else {
          String.format("%.2f ms", r.minDurationMs)
        }
        val rateStr = if (r.throughputMbPerSec >= 1024.0) {
          String.format("%.2f GB/s", r.throughputMbPerSec / 1024.0)
        } else {
          String.format("%.2f MB/s", r.throughputMbPerSec)
        }
        out.println(
          "| ${r.fileSizeMb} MB | `${r.algorithm}` | `${r.maxHashBytesLabel}` | " +
            "$latStr | $minStr | $rateStr |"
        )
      }
      out.println()
      out.println("## 3. End-to-End CLI Scan Benchmark (3x 50MB files = 150MB total)")
      out.println("| Algorithm | `--max-hash-bytes` | Ingestion Time |")
      out.println("| :--- | :--- | :--- |")
      for ((algo, label, ms) in cliResults) {
        out.println(String.format("| `%s` | `%s` | %.2f ms |", algo, label, ms))
      }
    }
    println("Benchmark Markdown summary written to: ${mdFile.absolutePath}")
  }
}
