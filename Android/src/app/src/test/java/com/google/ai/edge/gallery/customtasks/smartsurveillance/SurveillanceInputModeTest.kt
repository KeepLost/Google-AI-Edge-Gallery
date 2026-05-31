package com.google.ai.edge.gallery.customtasks.smartsurveillance

import org.junit.Assert.assertEquals
import org.junit.Test

class SurveillanceInputModeTest {
  @Test
  fun resolveInput_respectsRulePrefix() {
    val resolved = SurveillanceInputMode.resolve(defaultMode = SurveillanceInputMode.Ask, input = "/rule 有人靠近")

    assertEquals(SurveillanceInputMode.CreateRule, resolved.mode)
    assertEquals("有人靠近", resolved.text)
  }

  @Test
  fun resolveInput_respectsAskPrefix() {
    val resolved = SurveillanceInputMode.resolve(defaultMode = SurveillanceInputMode.CreateRule, input = "/ask 画面里有什么")

    assertEquals(SurveillanceInputMode.Ask, resolved.mode)
    assertEquals("画面里有什么", resolved.text)
  }

  @Test
  fun resolveInput_usesDefaultModeWithoutPrefix() {
    val resolved = SurveillanceInputMode.resolve(defaultMode = SurveillanceInputMode.CreateRule, input = "有人靠近")

    assertEquals(SurveillanceInputMode.CreateRule, resolved.mode)
    assertEquals("有人靠近", resolved.text)
  }
}
