package com.google.ai.edge.gallery.customtasks.smartsurveillance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SurveillancePromptConfigTest {
  @Test
  fun sanitize_trimsAndLimitsGuidance() {
    val longText = " x ".repeat(600)

    val sanitized = SurveillancePromptGuidance.sanitize(rule = "  rule  ", ask = longText, analysis = "\nanalysis\n")

    assertEquals("rule", sanitized.rule)
    assertTrue(sanitized.ask.length <= SurveillancePromptGuidance.MAX_GUIDANCE_CHARS)
    assertEquals("analysis", sanitized.analysis)
  }

  @Test
  fun defaults_areNonEmpty() {
    val defaults = SurveillancePromptGuidance.defaults()

    assertTrue(defaults.rule.isNotBlank())
    assertTrue(defaults.ask.isNotBlank())
    assertTrue(defaults.analysis.isNotBlank())
  }
}
