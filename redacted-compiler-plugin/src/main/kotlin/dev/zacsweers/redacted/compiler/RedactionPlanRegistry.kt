// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.redacted.compiler

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Per-compilation handoff point between the FIR and IR stages.
 *
 * The FIR checkers register validated [RedactionPlan]s here and the IR generation extension
 * restores them by [RedactionPlanKey] to execute generation. The registry only ever holds
 * immutable, symbol-free plans keyed by serializable [RedactionPlanKey]s.
 */
public class RedactionPlanRegistry {
  private val plans = ConcurrentHashMap<RedactionPlanKey, RedactionPlan>()
  private val generations = ConcurrentHashMap<RedactionPlanKey, AtomicInteger>()

  /** Registers a validated [plan]. Registration is idempotent per [RedactionPlan.key]. */
  public fun register(plan: RedactionPlan) {
    plans[plan.key] = plan
  }

  /** Restores the plan registered for [key], or `null` if the class is not redaction-eligible. */
  public fun planFor(key: RedactionPlanKey): RedactionPlan? = plans[key]

  /** Records that IR generated an implementation for [key]. */
  public fun recordGeneration(key: RedactionPlanKey) {
    generations.getOrPut(key) { AtomicInteger() }.incrementAndGet()
  }

  /** All registered plans, in registration order. */
  public fun plans(): List<RedactionPlan> = plans.values.toList()

  /**
   * Returns a list of human-readable errors for any registered plan that was not generated
   * exactly once. An empty list means every successful plan produced exactly one implementation.
   */
  public fun validateEachPlanGeneratedExactlyOnce(): List<String> = buildList {
    for ((key, _) in plans) {
      when (val count = generations[key]?.get() ?: 0) {
        1 -> Unit
        0 -> add("Plan for '${key.serialize()}' was registered but never generated.")
        else -> add("Plan for '${key.serialize()}' was generated $count times, expected exactly 1.")
      }
    }
  }
}
