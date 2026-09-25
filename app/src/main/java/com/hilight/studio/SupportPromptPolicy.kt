package com.hilight.studio

internal const val FREE_STYLE_COUNT = 5

internal fun wasSupportPromptShownForVersion(
    lastPromptedVersion: Int,
    currentVersion: Int,
): Boolean = lastPromptedVersion == currentVersion

/** Pure decision logic shared by the Play supporter prompt and its unit tests. */
internal fun shouldOfferSupportChoice(
    seenPatternKeys: Set<String>,
    promptShownThisVersion: Boolean,
    supporterOwned: Boolean,
): Boolean = !promptShownThisVersion &&
    !supporterOwned &&
    seenPatternKeys.size >= FREE_STYLE_COUNT
