// Copyright (C) 2021 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
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
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.addArgument
import org.jetbrains.kotlin.ir.types.isArray
import org.jetbrains.kotlin.ir.util.isPrimitiveArray
import org.jetbrains.kotlin.ir.util.parentClassOrNull
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.ir.util.properties

/**
 * Restores [RedactionPlan]s produced by the FIR stage from the [planRegistry] and only executes
 * the generation they describe. All annotation interpretation and validation happens in FIR.
 */
internal class RedactedIrVisitor(
  private val pluginContext: IrPluginContext,
  private val planRegistry: RedactionPlanRegistry,
  private val irAdapter: RedactedIrAdapter,
) : IrElementTransformerVoidWithContext() {

  override fun visitFunctionNew(declaration: IrFunction): IrStatement {
    if (declaration !is IrSimpleFunction) return super.visitFunctionNew(declaration)
    if (!irAdapter.isToStringFromAny(declaration)) return super.visitFunctionNew(declaration)
    if (declaration.origin == RedactedOrigin) return super.visitFunctionNew(declaration)

    val declarationParent =
      declaration.parentClassOrNull ?: return super.visitFunctionNew(declaration)
    val key = irAdapter.planKeyOf(declarationParent) ?: return super.visitFunctionNew(declaration)
    val plan = planRegistry.planFor(key) ?: return super.visitFunctionNew(declaration)

    declaration.convertToGeneratedToString(plan, declarationParent)
    planRegistry.recordGeneration(key)

    return super.visitFunctionNew(declaration)
  }

  private fun IrSimpleFunction.convertToGeneratedToString(plan: RedactionPlan, parent: IrClass) {
    origin = RedactedOrigin

    val constructorParamsByName =
      parent.primaryConstructor
        ?.let(irAdapter::regularValueParameters)
        .orEmpty()
        .associateBy { it.name.asString() }
    val irPropertiesByName = parent.properties.associateBy { it.name.asString() }

    body =
      DeclarationIrBuilder(pluginContext, symbol).irBlockBody {
        generateToStringMethodBody(
          irClass = parent,
          irFunction = this@convertToGeneratedToString,
          plan = plan,
          irPropertiesByName = irPropertiesByName,
          constructorParamsByName = constructorParamsByName,
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
    irPropertiesByName: Map<String, IrProperty>,
    constructorParamsByName: Map<String, IrValueParameter>,
  ) {
    val irConcat = irConcat()
    irConcat.addArgument(irString(irClass.name.asString() + "("))
    if (plan.classIsRedacted && !plan.classIsUnredacted && !plan.hasUnredactedProperties) {
      irConcat.addArgument(irString(plan.replacement))
    } else {
      var first = true
      for (property in plan.properties) {
        if (!first) irConcat.addArgument(irString(", "))

        irConcat.addArgument(irString(property.name + "="))
        val redactProperty =
          property.isRedacted ||
            (plan.classIsRedacted && !property.isUnredacted) ||
            (plan.supertypeIsRedacted && !plan.classIsUnredacted && !property.isUnredacted)
        if (redactProperty) {
          irConcat.addArgument(irString(plan.replacement))
        } else {
          val irPropertyValue =
            irGetField(receiver(irFunction), irPropertiesByName.getValue(property.name).backingField!!)

          val param = constructorParamsByName.getValue(property.name)
          val irPropertyStringValue =
            if (param.type.isArray() || param.type.isPrimitiveArray()) {
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
