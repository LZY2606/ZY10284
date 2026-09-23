// Copyright (C) 2022 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.redacted.compiler.fir

import dev.zacsweers.redacted.compiler.RedactionPlan
import dev.zacsweers.redacted.compiler.RedactionPlanStore
import org.jetbrains.kotlin.descriptors.isEnumEntry
import org.jetbrains.kotlin.descriptors.isObject
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.DeclarationCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirClassChecker
import org.jetbrains.kotlin.fir.analysis.extensions.FirAdditionalCheckersExtension
import org.jetbrains.kotlin.fir.declarations.FirClass
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.toAnnotationClassIdSafe
import org.jetbrains.kotlin.fir.declarations.utils.isEnumClass
import org.jetbrains.kotlin.fir.declarations.utils.isExpect
import org.jetbrains.kotlin.fir.declarations.utils.isExternal
import org.jetbrains.kotlin.fir.declarations.utils.isFinal
import org.jetbrains.kotlin.fir.declarations.utils.isInlineOrValue
import org.jetbrains.kotlin.fir.declarations.utils.isLocal
import org.jetbrains.kotlin.fir.declarations.utils.nameOrSpecialName
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.name.ClassId

public class RedactedFirExtensionRegistrar(
  private val redactedAnnotations: Set<ClassId>,
  private val unRedactedAnnotations: Set<ClassId>,
  private val replacementString: String,
  private val planStore: RedactionPlanStore,
) : FirExtensionRegistrar() {
  override fun ExtensionRegistrarContext.configurePlugin() {
    +RedactedFirBuiltIns.getFactory(
      redactedAnnotations,
      unRedactedAnnotations,
      replacementString,
      planStore,
    )
    +::FirRedactedCheckers
  }
}

internal class FirRedactedCheckers(session: FirSession) : FirAdditionalCheckersExtension(session) {
  override val declarationCheckers: DeclarationCheckers =
    object : DeclarationCheckers() {
      override val classCheckers: Set<FirClassChecker>
        get() = setOf(FirRedactedDeclarationChecker)
    }
}

internal object FirRedactedDeclarationChecker : FirClassChecker(MppCheckerKind.Common) {

  context(context: CheckerContext, reporter: DiagnosticReporter)
  override fun check(declaration: FirClass) {
    val scan = RedactedFirAdapter(context.session).scan(declaration)
    val classRedactedAnnotations = scan.classRedactedAnnotations
    val classUnRedactedAnnotations = scan.classUnredactedAnnotations
    val classIsRedacted = classRedactedAnnotations.isNotEmpty()
    val classIsUnRedacted = classUnRedactedAnnotations.isNotEmpty()
    val redactedSupertype = scan.redactedSupertype

    val redactedProperties = mutableMapOf<FirPropertySymbol, Pair<FirAnnotation, ClassId>>()
    val unredactedProperties = mutableMapOf<FirPropertySymbol, Pair<FirAnnotation, ClassId>>()
    for (property in scan.properties) {
      property.redactedAnnotation?.let { redactedProperties[property.symbol] = it }
      property.unredactedAnnotation?.let { unredactedProperties[property.symbol] = it }
    }
    val anyRedacted = redactedProperties.isNotEmpty()
    val anyUnredacted = unredactedProperties.isNotEmpty()
    val customToStringFunction = scan.customToStringFunction

    val redactedName = {
      redactedProperties.values.firstOrNull()?.second?.shortClassName?.asString()
        ?: classRedactedAnnotations.firstOrNull()?.second?.shortClassName?.asString()
    }

    val unRedactedName = {
      unredactedProperties.values.firstOrNull()?.second?.shortClassName?.asString()
        ?: classUnRedactedAnnotations.firstOrNull()?.second?.shortClassName?.asString()
    }

    if (classIsRedacted || redactedSupertype != null || classIsUnRedacted || anyRedacted) {
      if (customToStringFunction != null) {
        reporter.reportOn(
          customToStringFunction.source,
          RedactedDiagnostics.REDACTED_ERROR,
          "@${redactedName()} is only supported on data or value classes that do *not* have a custom toString() function. Please remove the function or remove the @${redactedName()} annotations.",
        )
        return
      }
      if (
        declaration.isInstantiableEnum ||
          declaration.isEnumClass ||
          declaration.classKind.isEnumEntry
      ) {
        reporter.reportOn(
          declaration.source,
          RedactedDiagnostics.REDACTED_ERROR,
          "@${redactedName()} does not support enum classes or entries!",
        )
        return
      }
      if (declaration.isFinal && !(declaration.status.isData || declaration.isInlineOrValue)) {
        reporter.reportOn(
          declaration.source,
          RedactedDiagnostics.REDACTED_ERROR,
          "@${redactedName()} is only supported on data or value classes!",
        )
        return
      }
      if (declaration.isInlineOrValue && !classIsRedacted) {
        reporter.reportOn(
          declaration.source,
          RedactedDiagnostics.REDACTED_ERROR,
          "@${redactedName()} is redundant on value class properties, just annotate the class instead.",
        )
        return
      }
      if (declaration.classKind.isObject) {
        if (redactedSupertype == null) {
          val classAnnotation = classRedactedAnnotations.first().first
          reporter.reportOn(
            classAnnotation.source,
            RedactedDiagnostics.REDACTED_ERROR,
            "@${classAnnotation.toAnnotationClassIdSafe(context.session)?.shortClassName?.asString()} is useless on object classes.",
          )
          return
        } else if (classIsUnRedacted) {
          reporter.reportOn(
            classUnRedactedAnnotations.firstOrNull()?.first?.source,
            RedactedDiagnostics.REDACTED_ERROR,
            "@${unRedactedName()} is useless on object classes.",
          )
          return
        }
      }
      if (classIsRedacted && classIsUnRedacted) {
        reporter.reportOn(
          declaration.source,
          RedactedDiagnostics.REDACTED_ERROR,
          "@${redactedName()} and @${unRedactedName()} cannot be applied to a single class.",
        )
        return
      }
      if (classIsUnRedacted && redactedSupertype == null) {
        reporter.reportOn(
          declaration.source,
          RedactedDiagnostics.REDACTED_ERROR,
          "@${unRedactedName()} cannot be applied to a class unless a supertype is marked @${redactedName()}.",
        )
        return
      }
      if (anyUnredacted && (!classIsRedacted && redactedSupertype == null)) {
        reporter.reportOn(
          declaration.source,
          RedactedDiagnostics.REDACTED_ERROR,
          "@${unRedactedName()} should only be applied to properties in a class or a supertype is marked @${redactedName()}.",
        )
        return
      }
      if (!(classIsRedacted xor anyRedacted xor (redactedSupertype != null))) {
        val redactedName =
          redactedProperties.values.firstOrNull()?.second
            ?: classRedactedAnnotations
              .firstOrNull()
              ?.first
              ?.toAnnotationClassIdSafe(context.session)
            ?: redactedSupertype?.redactedClassId
            ?: error("Not possible!")

        val message = buildString {
          appendLine("@${redactedName.shortClassName.asString()} detected on multiple targets:")
          if (classIsRedacted) {
            appendLine("class: '${declaration.nameOrSpecialName.asString()}'")
          }
          if (anyRedacted) {
            appendLine(
              "properties: ${redactedProperties.keys.joinToString(", ") { "'${it.name.asString()}'" }}"
            )
          }
          redactedSupertype?.clazz?.let { appendLine("supertype: ${it.classId}") }
        }

        if (classIsRedacted) {
          reporter.reportOn(
            classRedactedAnnotations.first().first.source,
            RedactedDiagnostics.REDACTED_ERROR,
            message,
          )
        } else {
          // Supertype
          reporter.reportOn(
            redactedSupertype?.ref?.source,
            RedactedDiagnostics.REDACTED_ERROR,
            message,
          )
        }
        for ((_, annotationAndId) in redactedProperties) {
          reporter.reportOn(
            annotationAndId.first.source,
            RedactedDiagnostics.REDACTED_ERROR,
            message,
          )
        }
        return
      }
    }

    // Diagnostics passed (or nothing to check). Hand a validated plan off to IR generation.
    registerPlan(declaration, scan)
  }

  context(context: CheckerContext)
  private fun registerPlan(declaration: FirClass, scan: RedactedFirAdapter.ClassScan) {
    // Plans are keyed by ClassId, so local and anonymous classes cannot participate.
    if (declaration !is FirRegularClass || declaration.isLocal) return

    val builtIns = context.session.redactedFirBuiltIns
    val classIsRedacted = scan.classRedactedAnnotations.isNotEmpty()
    val classIsUnredacted = scan.classUnredactedAnnotations.isNotEmpty()
    val supertypeIsRedacted = scan.redactedSupertype != null
    val anyRedactedProperty = scan.constructorProperties.any { it.redactedAnnotation != null }
    if (
      !(classIsRedacted || supertypeIsRedacted || classIsUnredacted || anyRedactedProperty)
    ) {
      return
    }

    val plan =
      RedactionPlan(
        classId = declaration.symbol.classId,
        eligibility =
          when {
            declaration.isInlineOrValue -> RedactionPlan.Eligibility.VALUE_CLASS
            declaration.classKind.isObject -> RedactionPlan.Eligibility.OBJECT
            declaration.status.isData -> RedactionPlan.Eligibility.DATA_CLASS
            else -> RedactionPlan.Eligibility.OTHER
          },
        properties =
          scan.constructorProperties.map { property ->
            RedactionPlan.Property(
              name = property.name,
              isRedacted = property.redactedAnnotation != null,
              isUnredacted = property.unredactedAnnotation != null,
            )
          },
        effectiveRule =
          when {
            classIsRedacted -> RedactionPlan.EffectiveRule.CLASS
            supertypeIsRedacted -> RedactionPlan.EffectiveRule.SUPERTYPE
            else -> RedactionPlan.EffectiveRule.PROPERTY
          },
        classIsUnredacted = classIsUnredacted,
        replacement = builtIns.replacementString,
        existingToStringPolicy = RedactionPlan.ExistingToStringPolicy.REPLACE_GENERATED,
        sourceOrigin = context.containingFile?.name ?: "<unknown>",
      )
    builtIns.planStore.register(plan)
  }

  private val FirClass.isInstantiableEnum: Boolean
    get() = isEnumClass && !isExpect && !isExternal
}
