package com.hilight.studio

internal const val FREE_STYLE_COUNT = 5

internal fun wasSupportPromptShownForVersion(
    lastPromptedVersion: Int,
    currentVersion: Int,
): Boolean = lastPromptedVersion == currentVersion

/**
 * Whether to offer the supporter choice on the first open after an app update. [lastSeenVersion]
 * is 0 when no version was ever recorded (installs before this check existed, and fresh installs);
 * then [installedByUpdate] (Android's last update time is after its first install time) decides.
 */
internal fun shouldOfferSupportAfterUpdate(
    lastSeenVersion: Int,
    installedByUpdate: Boolean,
    currentVersion: Int,
    promptShownThisVersion: Boolean,
    supporterOwned: Boolean,
): Boolean {
    val updated = if (lastSeenVersion > 0) lastSeenVersion < currentVersion else installedByUpdate
    return updated && !promptShownThisVersion && !supporterOwned
}

internal enum class UpdateOfferDecision {
    /** Offer the choice now; the version is then recorded as seen. */
    SHOW,
    /** Nothing to offer for this version; record it as seen. */
    SKIP,
    /** Google Play hasn't said whether the user is a supporter. Leave the version unrecorded. */
    WAIT,
}

/**
 * Settles the after-update offer. The choice is offered only once Play has answered whether the
 * user already supports the app, so a supporter who bought on another device is never asked; until
 * then the decision waits for a later open instead of being lost.
 */
internal fun decideSupportOfferAfterUpdate(
    lastSeenVersion: Int,
    installedByUpdate: Boolean,
    currentVersion: Int,
    promptShownThisVersion: Boolean,
    supporterOwned: Boolean,
    ownershipKnown: Boolean,
): UpdateOfferDecision = when {
    !shouldOfferSupportAfterUpdate(
        lastSeenVersion, installedByUpdate, currentVersion, promptShownThisVersion, supporterOwned,
    ) -> UpdateOfferDecision.SKIP
    !ownershipKnown -> UpdateOfferDecision.WAIT
    else -> UpdateOfferDecision.SHOW
}

internal enum class SupporterFlag { LIFETIME, MONTHLY }

/** Supporter flags a granted purchase saves, so an offline start still knows the user supports. */
internal fun supporterFlagsToSave(grantsLifetime: Boolean, grantsMonthly: Boolean): Set<SupporterFlag> =
    buildSet {
        if (grantsLifetime) add(SupporterFlag.LIFETIME)
        if (grantsMonthly) add(SupporterFlag.MONTHLY)
    }

/**
 * Whether the supporter card shows the purchase status line. A supporter still sees it while an
 * acknowledgement has failed: Play refunds an unacknowledged purchase, so "Try again" must stay.
 */
internal fun showsPurchaseStatus(supporterOwned: Boolean, acknowledgementFailed: Boolean): Boolean =
    !supporterOwned || acknowledgementFailed

/** Pure decision logic shared by the Play supporter prompt and its unit tests. */
internal fun shouldOfferSupportChoice(
    seenPatternKeys: Set<String>,
    candidatePatternKey: String,
    promptShownThisVersion: Boolean,
    supporterOwned: Boolean,
): Boolean = !promptShownThisVersion &&
    !supporterOwned &&
    (
        seenPatternKeys.size > FREE_STYLE_COUNT ||
            (seenPatternKeys.size == FREE_STYLE_COUNT && candidatePatternKey !in seenPatternKeys)
        )
