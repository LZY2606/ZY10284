// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.redacted.compiler.fir

import dev.zacsweers.redacted.compiler.RedactionPlanRegistry
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.extensions.FirExtensionSessionComponent
import org.jetbrains.kotlin.name.ClassId

internal class RedactedFirBuiltIns(
  session: FirSession,
  val redactedAnnotations: Set<ClassId>,
  val unRedactedAnnotations: Set<ClassId>,
  val replacementString: String,
  val planRegistry: RedactionPlanRegistry,
) : FirExtensionSessionComponent(session) {
  companion object {
    fun getFactory(
      redactedAnnotations: Set<ClassId>,
      unRedactedAnnotations: Set<ClassId>,
      replacementString: String,
      planRegistry: RedactionPlanRegistry,
    ) = Factory { session ->
      RedactedFirBuiltIns(
        session,
        redactedAnnotations,
        unRedactedAnnotations,
        replacementString,
        planRegistry,
      )
    }
  }
}

internal val FirSession.redactedFirBuiltIns: RedactedFirBuiltIns by
  FirSession.sessionComponentAccessor()

internal val FirSession.redactedAnnotations: Set<ClassId>
  get() = redactedFirBuiltIns.redactedAnnotations

internal val FirSession.unRedactedAnnotations: Set<ClassId>
  get() = redactedFirBuiltIns.unRedactedAnnotations

internal val FirSession.redactedReplacementString: String
  get() = redactedFirBuiltIns.replacementString

internal val FirSession.redactionPlanRegistry: RedactionPlanRegistry
  get() = redactedFirBuiltIns.planRegistry
