package tv.own.owntv.features.multiscreen

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The screen-level wiring of the 4.6.1 background fix, which a JVM test cannot compose: Home parks every
 * tile from the lifecycle callback itself, the return resumes through the same active-tile rule, and nothing
 * remembered by the Live TV preview or mpv can be restored underneath the tiles.
 */
class MultiscreenLifecycleWiringTest {

    private val screen by lazy {
        java.io.File("src/main/java/tv/own/owntv/features/multiscreen/MultiscreenScreen.kt").readText().replace("\r\n", "\n")
    }

    @Test fun `ON_STOP parks every tile directly, without waiting for a recomposition`() {
        val onStop = screen.substringAfter("Lifecycle.Event.ON_STOP -> {").substringBefore("Lifecycle.Event.ON_START")
        assertTrue("appVisible = false" in onStop)
        assertTrue("msState.setActive(MultiscreenDecoders.activeIds(" in onStop)
        assertTrue("visible = false" in onStop)
    }

    @Test fun `the return resumes through the same rule, after recomposition`() {
        assertTrue("Lifecycle.Event.ON_START -> appVisible = true" in screen)
        assertTrue("LaunchedEffect(channels, fullscreenChannelId, appVisible)" in screen)
        assertTrue("activeIds(channels.map { it.id }, fullscreenChannelId, appVisible)" in screen)
    }

    @Test fun `the observer is removed when Multiscreen closes`() {
        assertTrue("onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }" in screen)
    }

    @Test fun `entering Multiscreen leaves nothing to restore underneath the tiles`() {
        val entry = screen.substringAfter("livePreviewEngine.setAudioSuspended(true)").substringBefore("onDispose {")
        assertTrue("mpvPlayer.stop()" in entry)
        assertTrue("if (livePreviewEngine.currentUrl != null) livePreviewEngine.stop()" in entry)
        assertTrue("livePreviewEngine.discardBackgroundRestore()" in entry)
        assertTrue("mpvPlayer.discardBackgroundRestore()" in entry)
    }
}
