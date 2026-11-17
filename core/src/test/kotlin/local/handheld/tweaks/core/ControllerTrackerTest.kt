package local.handheld.tweaks.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerTrackerTest {
    private class FakeScheduler {
        var now = 0L
        private val pending = mutableListOf<Pair<Long, () -> Unit>>()
        fun schedule(delay: Long, block: () -> Unit): Cancellable {
            val entry = (now + delay) to block
            pending += entry
            return Cancellable { pending.remove(entry) }
        }
        fun advance(ms: Long) {
            now += ms
            pending.filter { it.first <= now }.forEach { pending.remove(it); it.second() }
        }
    }

    private val sched = FakeScheduler()
    private val events = mutableListOf<Boolean>()
    private val tracker = ControllerTracker(3000, { events += it }, sched::schedule)

    @Test fun isController_cases() {
        assertTrue(isController(0x401, true))
        assertTrue(isController(0x01000010, true))
        assertFalse(isController(0x401, false))
        assertFalse(isController(0x101, true))
        assertTrue(isController(0x101 or 0x401 or 0x01000010, true)) // GameSir reports keyboard|gamepad|joystick
    }

    @Test fun attach_firesImmediately() {
        tracker.added(14, true)
        assertEquals(listOf(true), events)
        assertTrue(tracker.connected)
    }

    @Test fun twoDevicesRemovedBackToBack_singleDetachAfterDebounce() {
        tracker.added(13, false)
        tracker.added(14, true)
        tracker.removed(14)
        tracker.removed(13)
        sched.advance(2999)
        assertEquals(listOf(true), events)
        sched.advance(1)
        assertEquals(listOf(true, false), events)
        assertFalse(tracker.connected)
    }

    @Test fun reattachWithinDebounce_noDetach() {
        tracker.added(14, true)
        tracker.removed(14)
        sched.advance(1000)
        tracker.added(15, true)
        sched.advance(10_000)
        assertEquals(listOf(true), events)
    }

    @Test fun nonControllerAdd_noChange() {
        tracker.added(20, false)
        sched.advance(5000)
        assertEquals(emptyList<Boolean>(), events)
    }

    @Test fun reset_setsStateSilently_thenDetachDebounces() {
        tracker.reset(setOf(14))
        assertTrue(tracker.connected)
        assertEquals(emptyList<Boolean>(), events)
        tracker.removed(14)
        sched.advance(3000)
        assertEquals(listOf(false), events)
    }

    @Test fun secondControllerWhileConnected_noExtraAttach() {
        tracker.added(14, true)
        tracker.added(30, true)
        tracker.removed(14)
        sched.advance(5000)
        assertEquals(listOf(true), events)
    }
}
