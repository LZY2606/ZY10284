// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.redacted.compiler

import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.types.isString
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.util.OperatorNameConventions

/**
 * Isolates compiler-version-specific IR symbol operations behind a single adapter so the rest of
 * the plugin only works with stable [RedactionPlan] contracts.
 */
public interface RedactedIrAdapter {
  /** Returns true if [function] is the `toString()` inherited from `kotlin.Any`. */
  public fun isToStringFromAny(function: IrSimpleFunction): Boolean

  /** Returns the regular (non receiver/context) value parameters of [constructor]. */
  public fun regularValueParameters(constructor: IrConstructor): List<IrValueParameter>

  /** Returns the [RedactionPlanKey] for [irClass], or `null` if it has no stable [ClassId]. */
  public fun planKeyOf(irClass: IrClass): RedactionPlanKey?

  public companion object Default : RedactedIrAdapter {
    override fun isToStringFromAny(function: IrSimpleFunction): Boolean =
      function.name == OperatorNameConventions.TO_STRING &&
        function.parameters.singleOrNull()?.kind == IrParameterKind.DispatchReceiver &&
        function.returnType.isString()

    override fun regularValueParameters(constructor: IrConstructor): List<IrValueParameter> =
      constructor.parameters.filter { it.kind == IrParameterKind.Regular }

    override fun planKeyOf(irClass: IrClass): RedactionPlanKey? =
      irClass.classId?.let(::RedactionPlanKey)
  }
}
