package com.hilight.studio

/** Prefer the missing trigger for a second rule; additional rules get independent identities. */
internal fun nextWholeAppRule(
    pkg: String,
    label: String,
    existing: List<AppRule>,
): AppRule? {
    val used = existing.filter { it.pkg == pkg && !it.isConversationRule }.map { it.trigger }.toSet()
    val trigger = if (Trigger.NOTIFICATION in used && Trigger.FOREGROUND !in used)
        Trigger.FOREGROUND else Trigger.NOTIFICATION
    return AppRule(pkg = pkg, label = label, trigger = trigger, stableId = java.util.UUID.randomUUID().toString())
}

/** Copies portable settings to another app while dropping notification identity tied to the source. */
internal fun copyWholeAppRule(
    source: AppRule,
    targetPkg: String,
    targetLabel: String,
): AppRule {
    require(!source.isConversationRule) { "conversation rules cannot be copied between apps" }
    return source.copy(
        stableId = java.util.UUID.randomUUID().toString(),
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

/** Filter before choosing a winner so a nonmatching text rule cannot swallow a fallback. */
internal fun matchesRuleKeyword(rule: AppRule, info: MessageInfo): Boolean = rule.keyword.isBlank() ||
    listOf(info.title, info.text, info.sender, info.conversationTitle)
        .joinToString(" ") { it.orEmpty() }.contains(rule.keyword.trim(), ignoreCase = true)

internal fun AppRule.withStableIdentity(): AppRule = copy(stableId = id)

internal fun resolveNotificationRule(rules: List<AppRule>, info: MessageInfo): AppRule? =
    ConversationMatch.resolve(rules.filter { matchesRuleKeyword(it, info) }.sortedBy { it.keyword.isBlank() }, info)
