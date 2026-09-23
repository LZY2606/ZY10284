// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.redacted.compiler

import org.jetbrains.kotlin.name.ClassId

/**
 * A serializable key identifying the class a [RedactionPlan] applies to.
 *
 * Keys are based on the fully qualified [ClassId] (package + nested class names) so that two
 * classes sharing a simple name in different packages or source sets never merge into one plan.
 */
public data class RedactionPlanKey(val classId: ClassId) {
  public fun serialize(): String = classId.asString()

  public companion object {
    public fun parse(serialized: String): RedactionPlanKey =
      RedactionPlanKey(ClassId.fromString(serialized))
  }
}

/** What kind of class a [RedactionPlan] was validated for. */
public enum class ClassEligibility {
  DATA_CLASS,
  VALUE_CLASS,
  OBJECT,
  OTHER,
}

/** The rule that effectively drives redaction for a class. */
public enum class EffectiveRule {
  /** The class itself is annotated with a redacted annotation. */
  CLASS_REDACTED,

  /** A supertype of the class is annotated with a redacted annotation. */
  SUPERTYPE_REDACTED,

  /** Individual properties are annotated with a redacted annotation. */
  PROPERTY_REDACTED,
}

/** How the generated `toString()` relates to any pre-existing `toString()` on the class. */
public enum class ExistingToStringPolicy {
  /** Replaces the compiler-synthesized `toString()` of a data or value class. */
  REPLACE_SYNTHESIZED_TO_STRING,

  /** Replaces the `toString()` inherited from `kotlin.Any`. */
  REPLACE_INHERITED_TO_STRING,
}

/** Where the redaction that produced a [RedactionPlan] originated from. */
public sealed interface SourceOrigin {
  /** A redacted annotation directly on the class. */
  public data class ClassAnnotation(val annotationClassId: ClassId) : SourceOrigin

  /** A redacted annotation on a (possibly transitive) supertype. */
  public data class SupertypeAnnotation(val supertypeClassId: ClassId, val annotationClassId: ClassId) :
    SourceOrigin

  /** Redacted annotations on individual properties. */
  public data class PropertyAnnotation(val annotationClassId: ClassId) : SourceOrigin
}

/** Redaction flags for a single constructor-backed property. */
public data class PropertyRedaction(
  val name: String,
  val isRedacted: Boolean,
  val isUnredacted: Boolean,
)

/**
 * Immutable stage contract between FIR and IR.
 *
 * FIR validates a class and produces a single [RedactionPlan] per eligible class, keyed by a
 * serializable [RedactionPlanKey]. IR restores plans by key and only executes the generation the
 * plan describes. Plans never hold bare FIR/IR symbols, so they can safely cross session and
 * source set boundaries.
 */
public data class RedactionPlan(
  val key: RedactionPlanKey,
  val eligibility: ClassEligibility,
  val properties: List<PropertyRedaction>,
  val classIsRedacted: Boolean,
  val classIsUnredacted: Boolean,
  val supertypeIsRedacted: Boolean,
  val replacement: String,
  val existingToStringPolicy: ExistingToStringPolicy,
  val sourceOrigin: SourceOrigin,
) {
  val effectiveRule: EffectiveRule
    get() =
      when {
        classIsRedacted -> EffectiveRule.CLASS_REDACTED
        supertypeIsRedacted -> EffectiveRule.SUPERTYPE_REDACTED
        else -> EffectiveRule.PROPERTY_REDACTED
      }

  val hasUnredactedProperties: Boolean
    get() = properties.any { it.isUnredacted }
}
