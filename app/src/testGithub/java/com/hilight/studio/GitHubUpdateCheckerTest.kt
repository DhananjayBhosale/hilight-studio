package com.hilight.studio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubUpdateCheckerTest {

    @Test
    fun `experimental prerelease is offered when its version is newer`() {
        val result = GitHubUpdateChecker.resolve(
            currentVersionName = "1.0.6",
            response = releases(
                release("v1.0.7-experimental", prerelease = true),
                release("v1.0.6-experimental", prerelease = true),
            ),
        )

        assertTrue(result is UpdateCheckResult.Available)
        val available = result as UpdateCheckResult.Available
        assertEquals("1.0.7", available.release.versionName)
        assertEquals("v1.0.7-experimental", available.release.tagName)
        assertEquals(
            "https://github.com/DhananjayBhosale/hilight-studio/releases/tag/v1.0.7-experimental",
            available.release.pageUrl,
        )
    }

    @Test
    fun `same or older published version reports current`() {
        val result = GitHubUpdateChecker.resolve(
            currentVersionName = "1.0.6",
            response = releases(
                release("v1.0.5-experimental", prerelease = true),
                release("v1.0.6-experimental", prerelease = true),
            ),
        )

        assertTrue(result is UpdateCheckResult.Current)
        assertEquals("1.0.6", (result as UpdateCheckResult.Current).latestVersionName)

        val developerBuildAhead = GitHubUpdateChecker.resolve(
            currentVersionName = "1.0.6",
            response = releases(release("v1.0.5-experimental", prerelease = true)),
        )
        assertTrue(developerBuildAhead is UpdateCheckResult.Current)
        assertEquals("1.0.5", (developerBuildAhead as UpdateCheckResult.Current).latestVersionName)
    }

    @Test
    fun `draft and malformed releases are ignored`() {
        val result = GitHubUpdateChecker.resolve(
            currentVersionName = "1.0.6",
            response = releases(
                release("v99.0.0-experimental", draft = true),
                release("nightly/latest"),
                release("v1.0.7-experimental"),
            ),
        )

        assertTrue(result is UpdateCheckResult.Available)
        assertEquals("1.0.7", (result as UpdateCheckResult.Available).release.versionName)
    }

    @Test
    fun `empty list and invalid response have distinct safe results`() {
        assertEquals(
            UpdateCheckResult.NoPublishedRelease,
            GitHubUpdateChecker.resolve("1.0.6", "[]"),
        )
        assertEquals(
            UpdateCheckResult.Failed,
            GitHubUpdateChecker.resolve("1.0.6", "not json"),
        )
    }

    private fun releases(vararg entries: String): String = entries.joinToString(
        prefix = "[",
        postfix = "]",
    )

    private fun release(
        tag: String,
        draft: Boolean = false,
        prerelease: Boolean = false,
    ): String = """
        {
          "tag_name": "$tag",
          "draft": $draft,
          "prerelease": $prerelease
        }
    """.trimIndent()
}
