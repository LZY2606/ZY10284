// Copyright (C) 2021 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.redacted.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment

/**
 * Executes the [RedactionPlan]s registered by the FIR stage in [planRegistry]. This extension
 * performs no annotation interpretation of its own.
 */
public class RedactedIrGenerationExtension(
  private val planRegistry: RedactionPlanRegistry,
  private val irAdapter: RedactedIrAdapter = RedactedIrAdapter.Default,
) : IrGenerationExtension {
  override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
    val redactedTransformer = RedactedIrVisitor(pluginContext, planRegistry, irAdapter)
    moduleFragment.transform(redactedTransformer, null)
  }
}
