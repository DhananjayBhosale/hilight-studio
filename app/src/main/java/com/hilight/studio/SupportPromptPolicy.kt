package com.hilight.studio

internal const val FREE_STYLE_COUNT = 5

/** Pure decision logic shared by the Play supporter prompt and its unit tests. */
internal fun shouldOfferSupportChoice(
    seenPatternKeys: Set<String>,
    candidatePatternKey: String,
    freeContinuationChosen: Boolean,
    supporterOwned: Boolean,
): Boolean = !freeContinuationChosen &&
    !supporterOwned &&
    candidatePatternKey !in seenPatternKeys &&
    seenPatternKeys.size >= FREE_STYLE_COUNT
