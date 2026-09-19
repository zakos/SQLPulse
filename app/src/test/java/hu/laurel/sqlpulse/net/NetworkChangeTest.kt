package hu.laurel.sqlpulse.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkChangeTest {

    private val wifi = NetworkStatus.Available(handle = 1L)
    private val mobile = NetworkStatus.Available(handle = 2L)

    @Test
    fun `a network arriving where there was none is not a loss`() {
        assertEquals(NetworkChange.CAME_UP, NetworkChange.between(NetworkStatus.Unknown, wifi))
        assertEquals(NetworkChange.CAME_UP, NetworkChange.between(NetworkStatus.Lost, wifi))
    }

    @Test
    fun `the default network going away is a loss`() {
        assertEquals(NetworkChange.LOST, NetworkChange.between(wifi, NetworkStatus.Lost))
    }

    @Test
    fun `moving from Wi-Fi to mobile data is a switch, not a loss and not nothing`() {
        // The case this whole package exists for: the sockets are dead, but Android never tells us
        // anything went away — one network simply replaces another.
        assertEquals(NetworkChange.SWITCHED, NetworkChange.between(wifi, mobile))
        assertEquals(NetworkChange.SWITCHED, NetworkChange.between(mobile, wifi))
    }

    @Test
    fun `the same network reported twice is not a change`() {
        assertEquals(NetworkChange.NONE, NetworkChange.between(wifi, wifi))
        assertEquals(
            NetworkChange.NONE,
            NetworkChange.between(wifi, NetworkStatus.Available(handle = 1L)),
        )
        assertEquals(NetworkChange.NONE, NetworkChange.between(NetworkStatus.Lost, NetworkStatus.Lost))
    }

    @Test
    fun `a loss seen before anything was ever up breaks nothing`() {
        // The app started in flight mode. There is no tunnel to tear down, and reporting a loss
        // here would fail a connection that was never made.
        assertEquals(NetworkChange.NONE, NetworkChange.between(NetworkStatus.Unknown, NetworkStatus.Lost))
        assertEquals(NetworkChange.NONE, NetworkChange.between(NetworkStatus.Lost, NetworkStatus.Unknown))
    }

    @Test
    fun `only a loss or a switch kills what is already open`() {
        assertTrue(NetworkChange.LOST.invalidatesConnections)
        assertTrue(NetworkChange.SWITCHED.invalidatesConnections)
        assertFalse(NetworkChange.CAME_UP.invalidatesConnections)
        assertFalse(NetworkChange.NONE.invalidatesConnections)
    }
}
