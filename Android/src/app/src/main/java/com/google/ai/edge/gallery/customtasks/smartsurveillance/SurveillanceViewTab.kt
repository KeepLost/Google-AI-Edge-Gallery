package com.google.ai.edge.gallery.customtasks.smartsurveillance

enum class SurveillanceViewTab(val label: String) {
  Ask("Ask"),
  Monitoring("Monitoring"),
  ;

  companion object {
    fun shouldPauseMonitoringOnSwitch(
      from: SurveillanceViewTab,
      to: SurveillanceViewTab,
      monitoringState: MonitoringState,
    ): Boolean =
      from == Monitoring && to == Ask && monitoringState == MonitoringState.Monitoring

    fun shouldStartMonitoringOnSwitch(from: SurveillanceViewTab, to: SurveillanceViewTab): Boolean = false
  }
}
