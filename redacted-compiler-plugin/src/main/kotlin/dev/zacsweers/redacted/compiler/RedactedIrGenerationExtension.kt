// Copyright (C) 2021 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.redacted.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment

public class RedactedIrGenerationExtension(
  private val planStore: RedactionPlanStore,
) : IrGenerationExtension {
  override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
    val redactedTransformer = RedactedIrVisitor(pluginContext, planStore)
    moduleFragment.transform(redactedTransformer, null)

    // Contract check: every plan FIR validated for this module must have generated exactly one
    // toString() implementation.
    val unconsumed = planStore.all().filter { it.key !in redactedTransformer.consumedPlanKeys }
    check(unconsumed.isEmpty()) {
      "Redaction plan(s) validated by FIR but never generated: " +
        unconsumed.joinToString(", ") { "${it.key} (${it.sourceOrigin})" }
    }
  }
}
