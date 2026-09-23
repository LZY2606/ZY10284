// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.redacted.compiler

import java.util.concurrent.ConcurrentHashMap
import org.jetbrains.kotlin.name.ClassId

/**
 * Per-compilation handoff of [RedactionPlan]s from FIR validation to IR generation. A new store is
 * created for every compilation (or test module) and shared by the registered FIR and IR
 * extensions, so plans never leak across modules.
 */
public class RedactionPlanStore {
  private val plans = ConcurrentHashMap<String, RedactionPlan>()

  public fun register(plan: RedactionPlan) {
    plans[plan.key] = plan
  }

  public fun planFor(classId: ClassId): RedactionPlan? = plans[RedactionPlan.keyOf(classId)]

  public fun all(): List<RedactionPlan> = plans.values.toList()
}
