// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.redacted.compiler.fir

import dev.zacsweers.redacted.compiler.ClassEligibility
import dev.zacsweers.redacted.compiler.ExistingToStringPolicy
import dev.zacsweers.redacted.compiler.PropertyRedaction
import dev.zacsweers.redacted.compiler.RedactionPlan
import dev.zacsweers.redacted.compiler.RedactionPlanKey
import dev.zacsweers.redacted.compiler.SourceOrigin
import dev.zacsweers.redacted.compiler.firstNotNullResult
import org.jetbrains.kotlin.descriptors.isObject
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.declarations.FirClass
import org.jetbrains.kotlin.fir.declarations.toAnnotationClassIdSafe
import org.jetbrains.kotlin.fir.declarations.utils.isInlineOrValue
import org.jetbrains.kotlin.fir.resolve.toSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirValueParameterSymbol
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.fir.types.ConeErrorType
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.coneTypeOrNull
import org.jetbrains.kotlin.name.ClassId

/**
 * Computes the immutable [RedactionPlan] stage contract for a class that passed all FIR
 * diagnostics. The plan is the single source of truth the IR stage executes against.
 */
internal object FirRedactionPlanComputer {

  context(context: CheckerContext)
  fun compute(
    declaration: FirClass,
    properties: List<FirPropertySymbol>,
    primaryConstructor: FirConstructorSymbol?,
    classRedactedAnnotations: List<ClassId>,
    classUnRedactedAnnotations: List<ClassId>,
  ): RedactionPlan? {
    val session = context.session
    // Local and anonymous classes have no stable, serializable key. They are not supported.
    val classSymbol = declaration.symbol as? FirRegularClassSymbol ?: return null
    val classId = classSymbol.classId
    if (classId.isLocal) return null

    val classIsRedacted = classRedactedAnnotations.isNotEmpty()
    val classIsUnredacted = classUnRedactedAnnotations.isNotEmpty()
    val redactedSupertype = findRedactedSupertype(declaration)

    val propertiesByName = properties.associateBy { it.name }
    val planProperties =
      primaryConstructor?.valueParameterSymbols.orEmpty().mapNotNull { parameter ->
        val property = propertiesByName[parameter.name] ?: return@mapNotNull null
        PropertyRedaction(
          name = parameter.name.asString(),
          isRedacted =
            property.hasRedactedAnnotation(session) || parameter.hasRedactedAnnotation(session),
          isUnredacted =
            property.hasUnredactedAnnotation(session) || parameter.hasUnredactedAnnotation(session),
        )
      }

    val eligibility =
      when {
        declaration.classKind.isObject -> ClassEligibility.OBJECT
        declaration.isInlineOrValue -> ClassEligibility.VALUE_CLASS
        declaration.status.isData -> ClassEligibility.DATA_CLASS
        else -> ClassEligibility.OTHER
      }

    val existingToStringPolicy =
      when (eligibility) {
        ClassEligibility.DATA_CLASS,
        ClassEligibility.VALUE_CLASS -> ExistingToStringPolicy.REPLACE_SYNTHESIZED_TO_STRING
        else -> ExistingToStringPolicy.REPLACE_INHERITED_TO_STRING
      }

    val sourceOrigin =
      when {
        classIsRedacted -> SourceOrigin.ClassAnnotation(classRedactedAnnotations.first())
        redactedSupertype != null ->
          SourceOrigin.SupertypeAnnotation(redactedSupertype.first, redactedSupertype.second)
        else ->
          SourceOrigin.PropertyAnnotation(
            properties.firstNotNullResult { it.redactedAnnotationClassId(session) }
              ?: primaryConstructor?.valueParameterSymbols.orEmpty().firstNotNullResult {
                it.redactedAnnotationClassId(session)
              }
              ?: session.redactedAnnotations.first()
          )
      }

    return RedactionPlan(
      key = RedactionPlanKey(classId),
      eligibility = eligibility,
      properties = planProperties,
      classIsRedacted = classIsRedacted,
      classIsUnredacted = classIsUnredacted,
      supertypeIsRedacted = redactedSupertype != null,
      replacement = session.redactedReplacementString,
      existingToStringPolicy = existingToStringPolicy,
      sourceOrigin = sourceOrigin,
    )
  }

  /**
   * Finds the nearest (possibly transitive) supertype annotated with a redacted annotation,
   * mirroring the historical IR-side `getAllSuperclasses()` behavior. Returns the supertype's
   * [ClassId] and the redacted annotation's [ClassId].
   */
  context(context: CheckerContext)
  private fun findRedactedSupertype(declaration: FirClass): Pair<ClassId, ClassId>? {
    val session = context.session
    val visited = mutableSetOf<ClassId>()
    val queue = ArrayDeque(declaration.superTypeRefs)
    while (queue.isNotEmpty()) {
      val ref = queue.removeFirst()
      val supertype = ref.coneTypeOrNull ?: continue
      if (supertype is ConeErrorType) continue
      if (supertype !is ConeClassLikeType) continue
      val superClassId = supertype.classId
      if (!visited.add(superClassId)) continue
      val symbol = superClassId.toSymbol() as? FirClassSymbol<*> ?: continue
      val redactedAnnotation =
        symbol.resolvedAnnotationClassIds.firstOrNull { it in session.redactedAnnotations }
      if (redactedAnnotation != null) {
        return superClassId to redactedAnnotation
      }
      queue.addAll(symbol.resolvedSuperTypeRefs)
    }
    return null
  }
}

private fun FirPropertySymbol.hasRedactedAnnotation(session: FirSession): Boolean =
  resolvedAnnotationsWithClassIds.any { it.toAnnotationClassIdSafe(session) in session.redactedAnnotations }

private fun FirPropertySymbol.hasUnredactedAnnotation(session: FirSession): Boolean =
  resolvedAnnotationsWithClassIds.any { it.toAnnotationClassIdSafe(session) in session.unRedactedAnnotations }

private fun FirPropertySymbol.redactedAnnotationClassId(session: FirSession): ClassId? =
  resolvedAnnotationsWithClassIds.firstNotNullResult { annotation ->
    annotation.toAnnotationClassIdSafe(session)?.takeIf { it in session.redactedAnnotations }
  }

private fun FirValueParameterSymbol.hasRedactedAnnotation(session: FirSession): Boolean =
  resolvedAnnotationsWithClassIds.any {
    it.toAnnotationClassIdSafe(session) in session.redactedAnnotations
  }

private fun FirValueParameterSymbol.hasUnredactedAnnotation(session: FirSession): Boolean =
  resolvedAnnotationsWithClassIds.any {
    it.toAnnotationClassIdSafe(session) in session.unRedactedAnnotations
  }

private fun FirValueParameterSymbol.redactedAnnotationClassId(session: FirSession): ClassId? =
  resolvedAnnotationsWithClassIds.firstNotNullResult { annotation ->
    annotation.toAnnotationClassIdSafe(session)?.takeIf { it in session.redactedAnnotations }
  }
