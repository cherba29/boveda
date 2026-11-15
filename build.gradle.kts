plugins {
  id("java")
  id("idea")

  kotlin("jvm") version "2.2.20"
  kotlin("plugin.serialization") version "2.2.20"
  id("org.jetbrains.kotlinx.kover") version "0.7.0"
  id("io.kotest") version "6.0.7"
  alias(libs.plugins.graalvm.native)
  alias(libs.plugins.ktlint)
  application
}

java {
  toolchain {
    languageVersion.set(JavaLanguageVersion.of(22))
  }
}

kotlin {
  compilerOptions {
    freeCompilerArgs.add("-Xcontext-parameters")
    freeCompilerArgs.add("-Xcontext-sensitive-resolution")
  }
}

group = "com.cherba29"
version = "1.0-SNAPSHOT"

repositories {
  mavenCentral()
}

dependencies {
  implementation(libs.clikt.core)
  implementation(libs.clikt.jvm)
  implementation(libs.clikt.markdown)
  implementation(libs.exposed.core)
  implementation(libs.exposed.dao)
  implementation(libs.exposed.jdbc)
  implementation(libs.guava)
  implementation(libs.guice)
  implementation(libs.guice.testlib)
  implementation(libs.h2)
  implementation(libs.hash4j)
  implementation(libs.logback.classic)
  implementation(libs.logback.core)
  implementation(libs.mordant.core)
  implementation(libs.slf4j)

  testImplementation(kotlin("test"))
  testImplementation(libs.kotest)
  testImplementation(libs.kotest.jvm)
  testImplementation(libs.kotest.assertions)
}

application {
  // Define the main class for the application.
  mainClass = "com.cherba29.boveda.MainKt"
}

graalvmNative {
  binaries {
    named("main") {
      imageName.set("boveda")
      mainClass.set("com.cherba29.boveda.MainKt")
      buildArgs.add("--no-fallback")
    }
  }
}

tasks.withType<Test>().configureEach {
  useJUnitPlatform()
}

tasks.test {
  testLogging {
    events("passed", "skipped", "failed")
    // Optional: prints System.out/System.err
    // showStandardStreams = true
  }
}

ktlint {
  android.set(true)
  outputToConsole.set(true)
}

koverReport {
  defaults {
    filters {
      excludes {
        // packages("com.ib.client.protobuf")
      }
    }
    verify {
      //  verify coverage when running the `check` task
      onCheck = true
      rule {
        // check this rule during verification
        isEnabled = true

        bound {
          minValue = 1
          maxValue = 99
          metric = kotlinx.kover.gradle.plugin.dsl.MetricType.LINE
        }
        minBound(2)
      }
    }
  }
}
