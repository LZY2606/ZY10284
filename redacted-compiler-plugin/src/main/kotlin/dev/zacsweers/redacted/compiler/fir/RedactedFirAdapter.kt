// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.redacted.compiler.fir

import dev.zacsweers.redacted.compiler.firstNotNullResult
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirClass
import org.jetbrains.kotlin.fir.declarations.FirDeclarationOrigin
import org.jetbrains.kotlin.fir.declarations.getAnnotationByClassId
import org.jetbrains.kotlin.fir.declarations.primaryConstructorIfAny
import org.jetbrains.kotlin.fir.declarations.processAllDeclarations
import org.jetbrains.kotlin.fir.declarations.toAnnotationClassIdSafe
import org.jetbrains.kotlin.fir.declarations.utils.isExtension
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.resolve.fullyExpandedType
import org.jetbrains.kotlin.fir.resolve.toClassSymbol
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.fir.types.ConeErrorType
import org.jetbrains.kotlin.fir.types.FirTypeRef
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.coneTypeOrNull
import org.jetbrains.kotlin.fir.types.isString
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.util.OperatorNameConventions

/**
 * Isolates compiler-version-specific FIR symbol operations used to scan redacted classes, so the
 * checker and plan builder stay version-agnostic.
 */
@OptIn(SymbolInternals::class)
internal class RedactedFirAdapter(private val session: FirSession) {

  /** A supertype of a scanned class that carries a redacted annotation. */
  internal class RedactedSupertype(
    val ref: FirTypeRef,
    val clazz: ConeClassLikeType,
    val redactedClassId: ClassId,
  )

  /** A member property with its effective redacted/unredacted annotations. */
  internal class ScannedProperty(
    val symbol: FirPropertySymbol,
    val redactedAnnotation: Pair<FirAnnotation, ClassId>?,
    val unredactedAnnotation: Pair<FirAnnotation, ClassId>?,
  ) {
    val name: String
      get() = symbol.name.asString()
  }

  /** Everything the checker and plan builder need to know about a class. */
  internal class ClassScan(
    val classRedactedAnnotations: List<Pair<FirAnnotation, ClassId>>,
    val classUnredactedAnnotations: List<Pair<FirAnnotation, ClassId>>,
    val redactedSupertype: RedactedSupertype?,
    val properties: List<ScannedProperty>,
    val constructorProperties: List<ScannedProperty>,
    val customToStringFunction: FirNamedFunctionSymbol?,
  )

  fun scan(declaration: FirClass): ClassScan {
    val classRedactedAnnotations =
      session.redactedAnnotations.mapNotNull { classId ->
        declaration.getAnnotationByClassId(classId, session)?.let { it to classId }
      }
    val classUnredactedAnnotations =
      session.unRedactedAnnotations.mapNotNull { classId ->
        declaration.getAnnotationByClassId(classId, session)?.let { it to classId }
      }

    val properties = mutableListOf<ScannedProperty>()
    var customToStringFunction: FirNamedFunctionSymbol? = null
    declaration.processAllDeclarations(session) { symbol ->
      if (symbol is FirPropertySymbol) {
        properties +=
          ScannedProperty(
            symbol = symbol,
            redactedAnnotation = symbol.findAnnotation(session.redactedAnnotations),
            unredactedAnnotation = symbol.findAnnotation(session.unRedactedAnnotations),
          )
      } else if (symbol is FirNamedFunctionSymbol) {
        if (
          customToStringFunction == null &&
            symbol.isToStringFromAny() &&
            symbol.origin == FirDeclarationOrigin.Source
        ) {
          customToStringFunction = symbol
        }
      }
    }

    return ClassScan(
      classRedactedAnnotations = classRedactedAnnotations,
      classUnredactedAnnotations = classUnredactedAnnotations,
      redactedSupertype = findRedactedSupertype(declaration),
      properties = properties,
      constructorProperties = constructorProperties(declaration, properties),
      customToStringFunction = customToStringFunction,
    )
  }

  /**
   * Finds the nearest redacted supertype of [declaration], walking the full supertype hierarchy
   * (including interfaces) the same way the IR side does. [RedactedSupertype.ref] always anchors
   * to a direct supertype of [declaration] for reporting, while [RedactedSupertype.clazz] is the
   * actual redacted ancestor.
   */
  private fun findRedactedSupertype(declaration: FirClass): RedactedSupertype? {
    val visited = mutableSetOf<ClassId>()
    val queue = ArrayDeque<Pair<FirTypeRef, ConeClassLikeType>>()

    fun enqueue(anchor: FirTypeRef, ref: FirTypeRef) {
      val supertype = ref.coneTypeOrNull ?: return
      if (supertype is ConeErrorType) return
      if (supertype !is ConeClassLikeType) return
      queue += anchor to supertype
    }

    for (ref in declaration.superTypeRefs) {
      enqueue(ref, ref)
    }

    while (queue.isNotEmpty()) {
      val (anchor, supertype) = queue.removeFirst()
      val classId = supertype.classId
      if (!visited.add(classId)) continue
      val symbol = supertype.lookupTag.toClassSymbol(session) ?: continue
      val redactedAnnotation =
        symbol.resolvedAnnotationClassIds.firstOrNull { it in session.redactedAnnotations }
      if (redactedAnnotation != null) {
        return RedactedSupertype(anchor, supertype, redactedAnnotation)
      }
      for (parentRef in symbol.resolvedSuperTypeRefs) {
        enqueue(anchor, parentRef)
      }
    }
    return null
  }

  /**
   * Redacted-relevant primary constructor properties, in constructor parameter order. Effective
   * annotations consider both the constructor parameter and its corresponding property, matching
   * how annotations are distributed across use-site targets.
   */
  private fun constructorProperties(
    declaration: FirClass,
    properties: List<ScannedProperty>,
  ): List<ScannedProperty> {
    val constructor = declaration.primaryConstructorIfAny(session) ?: return emptyList()
    val propertiesByName = properties.associateBy { it.symbol.name }
    return constructor.valueParameterSymbols.mapNotNull { parameter ->
      val firParameter = parameter.fir
      if (!firParameter.isVal && !firParameter.isVar) return@mapNotNull null
      val property = propertiesByName[parameter.name] ?: return@mapNotNull null
      ScannedProperty(
        symbol = property.symbol,
        redactedAnnotation =
          parameter.findAnnotation(session.redactedAnnotations) ?: property.redactedAnnotation,
        unredactedAnnotation =
          parameter.findAnnotation(session.unRedactedAnnotations) ?: property.unredactedAnnotation,
      )
    }
  }

  private fun FirBasedSymbol<*>.findAnnotation(
    annotationIds: Set<ClassId>
  ): Pair<FirAnnotation, ClassId>? =
    resolvedAnnotationsWithClassIds.firstNotNullResult {
      val classId = it.toAnnotationClassIdSafe(session)
      if (classId != null && classId in annotationIds) {
        it to classId
      } else {
        null
      }
    }

  private fun FirNamedFunctionSymbol.isToStringFromAny(): Boolean =
    name == OperatorNameConventions.TO_STRING &&
      dispatchReceiverType != null &&
      !isExtension &&
      valueParameterSymbols.isEmpty() &&
      resolvedReturnType.fullyExpandedType(session).isString
}
