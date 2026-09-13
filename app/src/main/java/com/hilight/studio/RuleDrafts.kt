package com.hilight.studio

/** Returns the first unoccupied whole-app trigger slot, without touching conversation rules. */
internal fun nextWholeAppRule(
    pkg: String,
    label: String,
    existing: List<AppRule>,
    initialColor: Int = AppRule.DEFAULT_COLOR,
): AppRule? {
    val notification = AppRule(pkg = pkg, label = label, trigger = Trigger.NOTIFICATION, color = initialColor)
    val foreground = notification.copy(trigger = Trigger.FOREGROUND)
    return listOf(notification, foreground).firstOrNull { candidate ->
        existing.none { it.id == candidate.id }
    }
}

/** Copies portable settings to another app while dropping notification identity tied to the source. */
internal fun copyWholeAppRule(
    source: AppRule,
    targetPkg: String,
    targetLabel: String,
): AppRule {
    require(!source.isConversationRule) { "conversation rules cannot be copied between apps" }
    return source.copy(
        pkg = targetPkg,
        label = targetLabel,
        keyword = "",
        conversationKey = null,
        conversationName = null,
        includeGroups = false,
        conversationIsGroup = false,
    )
}

/** True when saving this editor would replace a different rule at the destination identity. */
internal fun replacesExistingRule(
    existing: List<AppRule>,
    candidate: AppRule,
    openedRule: AppRule,
    isNew: Boolean,
): Boolean = existing.any { saved ->
    saved.id == candidate.id && (isNew || saved != openedRule)
}
