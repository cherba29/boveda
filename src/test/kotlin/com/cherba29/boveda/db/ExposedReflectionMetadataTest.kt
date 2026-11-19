package com.cherba29.boveda.db

import kotlin.reflect.KClass
import kotlin.reflect.KMutableProperty1
import kotlin.reflect.KProperty1
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import kotlin.test.Test
import org.jetbrains.exposed.v1.core.AutoIncColumnType
import org.jetbrains.exposed.v1.core.BasicBinaryColumnType
import org.jetbrains.exposed.v1.core.BinaryColumnType
import org.jetbrains.exposed.v1.core.BlobColumnType
import org.jetbrains.exposed.v1.core.BooleanColumnType
import org.jetbrains.exposed.v1.core.ByteColumnType
import org.jetbrains.exposed.v1.core.CharColumnType
import org.jetbrains.exposed.v1.core.ColumnType
import org.jetbrains.exposed.v1.core.DecimalColumnType
import org.jetbrains.exposed.v1.core.DoubleColumnType
import org.jetbrains.exposed.v1.core.EntityIDColumnType
import org.jetbrains.exposed.v1.core.FloatColumnType
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.LargeTextColumnType
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.MediumTextColumnType
import org.jetbrains.exposed.v1.core.ShortColumnType
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.UUIDColumnType
import org.jetbrains.exposed.v1.core.VarCharColumnType

/**
 * Exercises Kotlin reflection on all Exposed ColumnType classes and DAO entities so that
 * the GraalVM Tracing Agent intercepts and registers all required reflection metadata.
 */
class ExposedReflectionMetadataTest {

  @Suppress("UNCHECKED_CAST")
  private fun <T : Any> exerciseReflection(instance: T) {
    val kClass = instance::class as KClass<T>
    val mutableProps = kClass.memberProperties.filterIsInstance<KMutableProperty1<T, Any?>>()
    for (prop in kClass.memberProperties) {
      try {
        (prop as KProperty1<T, *>).get(instance)
      } catch (_: Throwable) {}
    }
    for (prop in mutableProps) {
      try {
        val current = (prop as KProperty1<T, *>).get(instance)
        prop.set(instance, current)
      } catch (_: Throwable) {}
    }
    val primary = kClass.primaryConstructor
    if (primary != null && primary.parameters.all { it.isOptional }) {
      try {
        primary.callBy(emptyMap())
      } catch (_: Throwable) {}
    }
  }

  @Test
  fun `exercise reflection on all Exposed column types and entity classes`() {
    val instances: List<Any> = listOf(
      IntegerColumnType(),
      LongColumnType(),
      VarCharColumnType(255),
      BooleanColumnType(),
      ByteColumnType(),
      ShortColumnType(),
      DoubleColumnType(),
      FloatColumnType(),
      DecimalColumnType(10, 2),
      CharColumnType(1),
      TextColumnType(),
      MediumTextColumnType(),
      LargeTextColumnType(),
      BinaryColumnType(32),
      BasicBinaryColumnType(),
      BlobColumnType(),
      UUIDColumnType(),
      AutoIncColumnType(IntegerColumnType(), null, "seq"),
      AutoIncColumnType(LongColumnType(), null, "seq"),
      EntityIDColumnType(RepoSnapshots.id),
      EntityIDColumnType(RepoFolders.id),
      EntityIDColumnType(RepoFiles.id),
      EntityIDColumnType(RepoFileInstances.id)
    )

    for (instance in instances) {
      exerciseReflection(instance)
    }

    // Test ColumnType methods directly as well
    val ct: ColumnType<*> = IntegerColumnType()
    val n = ct.nullable
    ct.nullable = true
    ct.nullable = n

    // All table columns
    for (table in listOf(RepoSnapshots, RepoFolders, RepoFiles, RepoFileInstances)) {
      for (col in table.columns) {
        exerciseReflection(col.columnType)
      }
    }
  }
}
