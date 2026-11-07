package tv.own.owntv.core.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The proxy screen showed "Off" and empty fields after an app restart while the saved proxy was still
 * routing all traffic: the form was seeded from a placeholder default before the saved config loaded.
 */
class ProxyFormTest {

    private val saved = ProxyConfig(enabled = true, host = "127.0.0.1", port = 8899, username = "u", password = "p")

    @Test
    fun `the form shows exactly what is saved, credentials included`() {
        assertEquals(ProxyForm(true, "127.0.0.1", "8899", "u", "p"), ProxyForm.from(saved))
    }

    @Test
    fun `an unset port is an empty field, not 0`() {
        assertEquals("", ProxyForm.from(ProxyConfig()).port)
        assertEquals(ProxyForm(false, "", "", "", ""), ProxyForm.from(ProxyConfig()))
    }

    @Test
    fun `the screen's state is empty until the saved config arrives - never a placeholder Off`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            val persisted = MutableSharedFlow<ProxyConfig>(replay = 1)
            // The same shape SettingsViewModel.proxyConfig uses: nullable, null initial value.
            val state = persisted.stateIn(scope, SharingStarted.Eagerly, null)
            assertNull("nothing to seed a form from before DataStore answers", state.value)
            persisted.emit(saved)
            val first = withTimeout(5_000) { state.filterNotNull().first() }
            assertEquals(ProxyForm.from(saved), ProxyForm.from(first))
        } finally {
            scope.cancel()
        }
    }
}
