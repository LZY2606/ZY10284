// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.redacted.compiler

import org.jetbrains.kotlin.name.ClassId

/**
 * Immutable, symbol-free contract for a single validated redacted class.
 *
 * FIR validation produces these plans after all diagnostics pass and hands them to IR via a
 * [RedactionPlanStore]. IR restores plans by [key] and only executes the described generation.
 * Plans never hold FIR/IR symbols, so they safely cross the frontend/backend boundary, and they
 * are keyed by [ClassId] so same-named classes from different packages never merge.
 */
public data class RedactionPlan(
  /** Unique identity of the redacted class. Also the serializable [key] source. */
  val classId: ClassId,
  /** What kind of class FIR validated this to be. */
  val eligibility: Eligibility,
  /** Primary-constructor properties in declaration order, with their effective annotations. */
  val properties: List<Property>,
  /** The effective redaction rule driving generation. */
  val effectiveRule: EffectiveRule,
  /** Whether the class itself is annotated with an unredacted annotation. */
  val classIsUnredacted: Boolean,
  /** The replacement string to emit for redacted values. */
  val replacement: String,
  /** How IR should treat any pre-existing toString() on the class. */
  val existingToStringPolicy: ExistingToStringPolicy,
  /** Where this plan originated, for diagnostics and debugging. */
  val sourceOrigin: String,
) {
  /** Serializable identity of this plan. */
  public val key: String
    get() = keyOf(classId)

  public data class Property(
    val name: String,
    val isRedacted: Boolean,
    val isUnredacted: Boolean,
  )

  public enum class Eligibility {
    DATA_CLASS,
    VALUE_CLASS,
    OBJECT,
    OTHER,
  }

  public enum class EffectiveRule {
    /** The class itself is redacted. */
    CLASS,

    /** A supertype is redacted, so this class behaves as redacted. */
    SUPERTYPE,

    /** Only individual properties are redacted. */
    PROPERTY,
  }

  public enum class ExistingToStringPolicy {
    /** FIR verified no user-defined toString() exists; IR replaces the generated one. */
    REPLACE_GENERATED,
  }

  public companion object {
    public fun keyOf(classId: ClassId): String = classId.asString()
  }
}
