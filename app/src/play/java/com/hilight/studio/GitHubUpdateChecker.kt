package com.hilight.studio

internal data class GitHubRelease(
    val tagName: String,
    val versionName: String,
    val pageUrl: String,
)

internal sealed interface UpdateCheckResult {
    data class Available(val release: GitHubRelease) : UpdateCheckResult
    data class Current(val latestVersionName: String) : UpdateCheckResult
    data object NoPublishedRelease : UpdateCheckResult
    data object Failed : UpdateCheckResult
}

/** No network updater exists in the Play distribution flavor. */
internal object GitHubUpdateChecker {
    @Suppress("UNUSED_PARAMETER")
    fun check(currentVersionName: String): UpdateCheckResult = UpdateCheckResult.Failed
}
