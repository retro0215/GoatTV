package tv.own.owntv.core.isolation

import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.BuildConfig
import tv.own.owntv.core.launcher.LauncherIntegrationRepository
import tv.own.owntv.core.tv.TvHomeRepository
import tv.own.owntv.features.tutorials.VideoTutorialsEndpoints

class IsolatedTestBuildTest {
    private val prodUrl = "https://aaaaaaaaaaaaaaaaaaaa.supabase.co"
    private val stagingUrl = "https://bbbbbbbbbbbbbbbbbbbb.supabase.co"

    @Test
    fun `the isolated build reads staging without a switch and never production`() {
        val ep = VideoTutorialsEndpoints.resolve(true, false, stagingUrl, "sb_publishable_x", prodUrl, "prod", isolatedTestBuild = true)
        assertTrue(ep.isStaging)
        assertEquals(stagingUrl, ep.baseUrl)

        for ((url, key) in listOf("" to "", stagingUrl to "", stagingUrl to "sb_secret_x", "https://evil.example" to "k")) {
            val refused = VideoTutorialsEndpoints.resolve(true, true, url, key, prodUrl, "prod", isolatedTestBuild = true)
            assertEquals(VideoTutorialsEndpoints.UNREACHABLE_URL, refused.baseUrl)
            assertFalse(refused.anonKey == "prod")
        }
    }

    @Test
    fun `launcher rows are not published when publishing is refused`() = runBlocking {
        val tvHome = mockk<TvHomeRepository>(relaxed = true)
        val repo = LauncherIntegrationRepository(mockk(relaxed = true), mockk(relaxed = true), tvHome, publishingAllowed = { false })
        repo.refreshProfile(1L, allowBrowsableRequest = true)
        repo.refreshRecentLive(1L)
        repo.publishMovieProgress(1L, 2L, 3L, 4L)
        repo.publishEpisodeProgress(1L, 2L, 3L, 4L)
        coVerify(exactly = 0) { tvHome.refreshProfile(any(), any()) }
        coVerify(exactly = 0) { tvHome.refreshRecentLive(any(), any()) }
        coVerify(exactly = 0) { tvHome.publishMovieProgress(any(), any(), any(), any()) }
        coVerify(exactly = 0) { tvHome.publishEpisodeProgress(any(), any(), any(), any()) }

        val allowed = LauncherIntegrationRepository(mockk(relaxed = true), mockk(relaxed = true), tvHome, publishingAllowed = { true })
        allowed.refreshProfile(1L)
        coVerify(exactly = 1) { tvHome.refreshProfile(1L, false) }
    }

    @Test
    fun `build config invariants of the variant under test`() {
        assertEquals(BuildConfig.ISOLATED_TEST_BUILD, IsolatedTestBuild.active)
        if (BuildConfig.ISOLATED_TEST_BUILD) {
            assertTrue(BuildConfig.APPLICATION_ID.endsWith(".tutorialtest"))
            assertFalse(BuildConfig.PUSHWOOSH_ENABLED)
            // The Supabase project of this build is staging (same as the tutorials catalog) or unreachable.
            assertTrue(
                BuildConfig.SUPABASE_URL == "https://isolated-test-build.invalid" ||
                    BuildConfig.SUPABASE_URL == BuildConfig.TUTORIALS_STAGING_URL,
            )
            assertTrue(VideoTutorialsEndpoints.current().isStaging)
        } else {
            assertFalse(BuildConfig.APPLICATION_ID.endsWith(".tutorialtest"))
        }
    }
}
