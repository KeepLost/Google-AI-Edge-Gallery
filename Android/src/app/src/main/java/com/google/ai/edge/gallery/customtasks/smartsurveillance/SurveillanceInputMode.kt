package com.google.ai.edge.gallery.customtasks.smartsurveillance

enum class SurveillanceInputMode(val label: String) {
  Ask("普通询问"),
  CreateRule("新增规则"),
  ;

  data class Resolved(val mode: SurveillanceInputMode, val text: String)

  companion object {
    fun resolve(defaultMode: SurveillanceInputMode, input: String): Resolved {
      val trimmed = input.trim()
      return when {
        trimmed.startsWith("/rule", ignoreCase = true) ->
          Resolved(CreateRule, trimmed.removePrefixIgnoreCase("/rule").trim())
        trimmed.startsWith("/ask", ignoreCase = true) ->
          Resolved(Ask, trimmed.removePrefixIgnoreCase("/ask").trim())
        else -> Resolved(defaultMode, trimmed)
      }
    }

    private fun String.removePrefixIgnoreCase(prefix: String): String =
      if (startsWith(prefix, ignoreCase = true)) substring(prefix.length) else this
  }
}
