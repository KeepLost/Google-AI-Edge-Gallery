package com.google.ai.edge.gallery.customtasks.smartsurveillance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SurveillanceMonitoringStateTest {
  @Test
  fun deriveMonitoringState_returnsIdleNoRulesWhenNoActiveRules() {
    val state = SurveillanceMonitoringState.derive(activeRuleCount = 0, requestedMonitoring = true)

    assertEquals(MonitoringState.IdleNoRules, state)
  }

  @Test
  fun deriveMonitoringState_requiresExplicitMonitoringRequest() {
    val state = SurveillanceMonitoringState.derive(activeRuleCount = 1, requestedMonitoring = false)

    assertEquals(MonitoringState.ReadyPaused, state)
  }

  @Test
  fun deriveMonitoringState_entersMonitoringOnlyWithRulesAndRequest() {
    val state = SurveillanceMonitoringState.derive(activeRuleCount = 1, requestedMonitoring = true)

    assertEquals(MonitoringState.Monitoring, state)
  }

  @Test
  fun canStartMonitoring_requiresActiveRulesAndNoInference() {
    assertTrue(SurveillanceMonitoringState.canStartMonitoring(activeRuleCount = 1, inferenceOwner = InferenceOwner.None))
    assertFalse(SurveillanceMonitoringState.canStartMonitoring(activeRuleCount = 0, inferenceOwner = InferenceOwner.None))
    assertFalse(SurveillanceMonitoringState.canStartMonitoring(activeRuleCount = 1, inferenceOwner = InferenceOwner.Ask))
    assertFalse(SurveillanceMonitoringState.canStartMonitoring(activeRuleCount = 1, inferenceOwner = InferenceOwner.RuleCreation))
    assertFalse(SurveillanceMonitoringState.canStartMonitoring(activeRuleCount = 1, inferenceOwner = InferenceOwner.Monitoring))
  }

  @Test
  fun shouldStopMonitoringWhenNoActiveRules_returnsTrueOnlyForRunningMonitoring() {
    assertTrue(
      SurveillanceMonitoringState.shouldStopMonitoringWhenNoActiveRules(
        activeRuleCount = 0,
        monitoringState = MonitoringState.Monitoring,
      )
    )
    assertFalse(
      SurveillanceMonitoringState.shouldStopMonitoringWhenNoActiveRules(
        activeRuleCount = 1,
        monitoringState = MonitoringState.Monitoring,
      )
    )
    assertFalse(
      SurveillanceMonitoringState.shouldStopMonitoringWhenNoActiveRules(
        activeRuleCount = 0,
        monitoringState = MonitoringState.ReadyPaused,
      )
    )
  }
}
