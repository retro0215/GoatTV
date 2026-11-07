package tv.own.owntv.features.epg

import android.content.ComponentCallbacks2
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@Suppress("DEPRECATION")
class GuideMemoryPressureTest {
    @Test
    fun `the off-screen guide window is kept through ordinary pressure and leaving the app`() {
        for (level in listOf(
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN,
        )) assertFalse("level $level", GuideMemoryPressure.releases(level))
    }

    @Test
    fun `it is released when memory is critical or the process waits in the background`() {
        for (level in listOf(
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
            ComponentCallbacks2.TRIM_MEMORY_MODERATE,
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE,
        )) assertTrue("level $level", GuideMemoryPressure.releases(level))
    }
}
