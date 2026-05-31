package com.google.ai.edge.gallery.customtasks.smartsurveillance

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SurveillanceViewTabTest {
  @Test
  fun switchingFromMonitoringToAskShouldPauseMonitoring() {
    assertTrue(
      SurveillanceViewTab.shouldPauseMonitoringOnSwitch(
        from = SurveillanceViewTab.Monitoring,
        to = SurveillanceViewTab.Ask,
        monitoringState = MonitoringState.Monitoring,
      )
    )
  }

  @Test
  fun switchingToMonitoringDoesNotAutoStartMonitoring() {
    assertFalse(
      SurveillanceViewTab.shouldStartMonitoringOnSwitch(
        from = SurveillanceViewTab.Ask,
        to = SurveillanceViewTab.Monitoring,
      )
    )
  }
}
