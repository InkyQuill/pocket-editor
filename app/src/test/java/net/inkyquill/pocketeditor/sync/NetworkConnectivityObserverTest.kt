package net.inkyquill.pocketeditor.sync

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NetworkConnectivityObserverTest {
    @Test
    fun `emits once when validated connectivity is restored`() = runTest {
        val source = FakeValidatedNetworkSource(initiallyValidated = true)
        val observer = NetworkConnectivityObserver(source)
        val restored = async { observer.connected.firstEvent() }
        runCurrent()

        source.update(true)
        source.update(false)
        source.update(false)
        source.update(true)

        assertNotNull(restored.await())
        assertEquals(1, source.registrations)
    }

    @Test
    fun `does not emit while network remains validated`() = runTest {
        val source = FakeValidatedNetworkSource(initiallyValidated = true)
        val observer = NetworkConnectivityObserver(source)

        source.update(true)
        source.update(true)

        assertNull(withTimeoutOrNull(1) { observer.connected.firstEvent() })
    }

    private class FakeValidatedNetworkSource(initiallyValidated: Boolean) : ValidatedNetworkSource {
        private var validated = initiallyValidated
        private lateinit var listener: (Boolean) -> Unit
        var registrations = 0

        override fun isValidated() = validated

        override fun register(onValidatedChanged: (Boolean) -> Unit) {
            registrations++
            listener = onValidatedChanged
        }

        fun update(value: Boolean) {
            validated = value
            listener(value)
        }
    }

    private suspend fun kotlinx.coroutines.flow.Flow<Unit>.firstEvent(): Unit =
        first()
}
