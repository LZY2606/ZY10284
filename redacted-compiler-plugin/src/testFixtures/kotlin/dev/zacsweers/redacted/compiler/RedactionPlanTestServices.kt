// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.redacted.compiler

import org.jetbrains.kotlin.test.model.AfterAnalysisChecker
import org.jetbrains.kotlin.test.services.TestService
import org.jetbrains.kotlin.test.services.TestServices

/** Collects the per-module [RedactionPlanRegistry] instances created during a test run. */
class RedactionPlanRegistryService(testServices: TestServices) : TestService {
  val registries = mutableListOf<RedactionPlanRegistry>()
}

val TestServices.redactionPlanRegistryService: RedactionPlanRegistryService by
  TestServices.testServiceAccessor()

/**
 * Asserts that every [RedactionPlan] successfully registered by the FIR stage produced exactly
 * one generated implementation in the IR stage.
 */
class RedactionPlanGenerationChecker(testServices: TestServices) :
  AfterAnalysisChecker(testServices) {
  override fun check(suppressedFailures: Boolean) {
    val errors =
      testServices.redactionPlanRegistryService.registries.flatMap {
        it.validateEachPlanGeneratedExactlyOnce()
      }
    if (errors.isNotEmpty()) {
      throw AssertionError(
        buildString {
          appendLine("RedactionPlan contract violations:")
          errors.forEach { appendLine(" - $it") }
        }
      )
    }
  }
}
