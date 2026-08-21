package com.hilight.studio

/** Selects the one notification rule that should handle a notification. */
object RuleMatcher {
    fun notificationRuleFor(
        rules: List<AppRule>,
        packageName: String,
        searchableText: String,
    ): AppRule? {
        val enabled = rules.filter { it.enabled && it.trigger == Trigger.NOTIFICATION }
        val exact = enabled.filter { it.pkg == packageName }
        val candidates = if (exact.isNotEmpty()) exact else enabled.filter { it.isCatchAll }

        return candidates.firstOrNull { rule ->
            rule.keyword.isNotBlank() && searchableText.contains(rule.keyword.trim(), ignoreCase = true)
        } ?: candidates.firstOrNull { it.keyword.isBlank() }
    }
}
