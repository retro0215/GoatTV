package tv.own.owntv.features.epg

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression for the EPG-sync OutOfMemoryError: while an EPG import writes batch after batch, the
 * Guide must not reload its whole window each time (every reload held a full copy of the guide).
 */
class GuideReloadTriggersTest {

    private val settle = 30L

    @Test
    fun `one reload after the sync ends, with the final count`() = runBlocking {
        val count = MutableStateFlow(600_000)
        val idle = MutableStateFlow(true)
        val seen = mutableListOf<Int>()
        val job = async(Dispatchers.Default, CoroutineStart.UNDISPATCHED) {
            guideReloadTriggers(count, idle, settle).collect { seen += it }
        }
        delay(100)
        idle.value = false
        repeat(10) { i -> count.value = 600_000 + (i + 1) * 5_000; delay(settle * 2) }
        assertEquals("nothing while syncing", emptyList<Int>(), seen.toList())
        idle.value = true
        delay(300)
        job.cancel()
        assertEquals(listOf(650_000), seen.toList())
    }

    @Test
    fun `outside a sync, a data change still reloads once after settling`() = runBlocking {
        val count = MutableStateFlow(10)
        val idle = MutableStateFlow(true)
        val first = async(Dispatchers.Default, CoroutineStart.UNDISPATCHED) {
            withTimeoutOrNull(2_000) { guideReloadTriggers(count, idle, settle).take(1).toList(mutableListOf()) }
        }
        delay(100)
        count.value = 20
        assertEquals(listOf(20), first.await())
    }

    @Test
    fun `the initial value never triggers a reload (the screen loads itself)`() = runBlocking {
        val seen = mutableListOf<Int>()
        val job = async(Dispatchers.Default, CoroutineStart.UNDISPATCHED) {
            guideReloadTriggers(MutableStateFlow(5), MutableStateFlow(true), settle).collect { seen += it }
        }
        delay(200)
        job.cancel()
        assertEquals(emptyList<Int>(), seen.toList())
    }
}
