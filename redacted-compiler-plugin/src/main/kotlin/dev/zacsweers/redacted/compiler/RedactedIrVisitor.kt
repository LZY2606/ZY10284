// Copyright (C) 2021 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
@file:OptIn(UnsafeDuringIrConstructionAPI::class)

package dev.zacsweers.redacted.compiler

import org.jetbrains.kotlin.backend.common.IrElementTransformerVoidWithContext
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.IrBlockBodyBuilder
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irConcat
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetField
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.addArgument
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.isArray
import org.jetbrains.kotlin.ir.types.isString
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.isPrimitiveArray
import org.jetbrains.kotlin.ir.util.parentClassOrNull
import org.jetbrains.kotlin.ir.util.properties
import org.jetbrains.kotlin.util.OperatorNameConventions

/**
 * Executes [RedactionPlan]s produced by FIR validation. For each toString() on a class with a
 * registered plan, exactly one redacted implementation is generated; nothing is re-derived from
 * annotations here.
 */
internal class RedactedIrVisitor(
  private val pluginContext: IrPluginContext,
  private val planStore: RedactionPlanStore,
) : IrElementTransformerVoidWithContext() {

  private val _consumedPlanKeys = LinkedHashSet<String>()

  /** Keys of plans that generated their toString(), in consumption order. */
  val consumedPlanKeys: Set<String>
    get() = _consumedPlanKeys

  override fun visitFunctionNew(declaration: IrFunction): IrStatement {
    if (declaration !is IrSimpleFunction) return super.visitFunctionNew(declaration)
    if (!declaration.isToStringFromAny()) return super.visitFunctionNew(declaration)

    val declarationParent =
      declaration.parentClassOrNull ?: return super.visitFunctionNew(declaration)
    val classId = declarationParent.classId ?: return super.visitFunctionNew(declaration)
    val plan = planStore.planFor(classId) ?: return super.visitFunctionNew(declaration)

    check(_consumedPlanKeys.add(plan.key)) {
      "Redaction plan '${plan.key}' (${plan.sourceOrigin}) would generate a second toString() " +
        "implementation. Each plan must generate exactly one."
    }

    declaration.convertToGeneratedToString(declarationParent, plan)

    return super.visitFunctionNew(declaration)
  }

  private fun IrFunction.isToStringFromAny(): Boolean =
    name == OperatorNameConventions.TO_STRING &&
      parameters.singleOrNull()?.kind == IrParameterKind.DispatchReceiver &&
      returnType.isString()

  private fun IrSimpleFunction.convertToGeneratedToString(
    irClass: IrClass,
    plan: RedactionPlan,
  ) {
    origin = RedactedOrigin

    val irPropertiesByName = irClass.properties.associateBy { it.name.asString() }
    val plannedProperties =
      plan.properties.mapNotNull { property ->
        irPropertiesByName[property.name]?.let { property to it }
      }

    body =
      DeclarationIrBuilder(pluginContext, symbol).irBlockBody {
        generateToStringMethodBody(
          irClass = irClass,
          irFunction = this@convertToGeneratedToString,
          plan = plan,
          irProperties = plannedProperties,
        )
      }

    isFakeOverride = false
  }

  /**
   * The actual body of the toString method. Copied from
   * [org.jetbrains.kotlin.ir.util.DataClassMembersGenerator.MemberFunctionBuilder.generateToStringMethodBody]
   * .
   */
  private fun IrBlockBodyBuilder.generateToStringMethodBody(
    irClass: IrClass,
    irFunction: IrFunction,
    plan: RedactionPlan,
    irProperties: List<Pair<RedactionPlan.Property, IrProperty>>,
  ) {
    val irConcat = irConcat()
    irConcat.addArgument(irString(irClass.name.asString() + "("))
    val classIsRedacted = plan.effectiveRule == RedactionPlan.EffectiveRule.CLASS
    val supertypeIsRedacted = plan.effectiveRule == RedactionPlan.EffectiveRule.SUPERTYPE
    if (
      classIsRedacted && !plan.classIsUnredacted && irProperties.none { it.first.isUnredacted }
    ) {
      irConcat.addArgument(irString(plan.replacement))
    } else {
      var first = true
      for ((property, irProperty) in irProperties) {
        if (!first) irConcat.addArgument(irString(", "))

        irConcat.addArgument(irString(property.name + "="))
        val redactProperty =
          property.isRedacted ||
            (classIsRedacted && !property.isUnredacted) ||
            (supertypeIsRedacted && !plan.classIsUnredacted && !property.isUnredacted)
        if (redactProperty) {
          irConcat.addArgument(irString(plan.replacement))
        } else {
          val backingField = irProperty.backingField!!
          val irPropertyValue = irGetField(receiver(irFunction), backingField)

          val irPropertyStringValue =
            if (backingField.type.isArray() || backingField.type.isPrimitiveArray()) {
              irCall(
                  context.irBuiltIns.dataClassArrayMemberToStringSymbol,
                  context.irBuiltIns.stringType,
                )
                .apply { arguments[0] = irPropertyValue }
            } else {
              irPropertyValue
            }

          irConcat.addArgument(irPropertyStringValue)
        }
        first = false
      }
    }
    irConcat.addArgument(irString(")"))
    +irReturn(irConcat)
  }

  private fun IrBlockBodyBuilder.receiver(irFunction: IrFunction) =
    irGet(irFunction.dispatchReceiverParameter!!)
}
