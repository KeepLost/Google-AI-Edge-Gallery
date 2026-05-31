package com.google.ai.edge.gallery.customtasks.smartsurveillance

enum class InferenceOwner(val label: String) {
  None("Idle"),
  Ask("Answering"),
  RuleCreation("Creating rule"),
  Monitoring("Monitoring"),
}

enum class MonitoringState(val label: String) {
  IdleNoRules("No active rules"),
  ReadyPaused("Monitoring paused"),
  Monitoring("Monitoring active"),
}

object SurveillanceMonitoringState {
  fun derive(activeRuleCount: Int, requestedMonitoring: Boolean): MonitoringState =
    when {
      activeRuleCount <= 0 -> MonitoringState.IdleNoRules
      requestedMonitoring -> MonitoringState.Monitoring
      else -> MonitoringState.ReadyPaused
    }

  fun canStartMonitoring(activeRuleCount: Int, inferenceOwner: InferenceOwner): Boolean =
    activeRuleCount > 0 && inferenceOwner == InferenceOwner.None

  fun shouldStopMonitoringWhenNoActiveRules(
    activeRuleCount: Int,
    monitoringState: MonitoringState,
  ): Boolean = activeRuleCount <= 0 && monitoringState == MonitoringState.Monitoring
}
