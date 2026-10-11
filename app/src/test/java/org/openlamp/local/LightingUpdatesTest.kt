package org.openlamp.local

import org.junit.Assert.*
import org.junit.Test

class LightingUpdatesTest {
    private fun update(warmth: Int, preview: Boolean = true) = LightingUpdate(LightingValues(warmth, 80), preview)

    @Test fun rapidDragSendsTheNewestPositionWithoutOverlappingTransmissions() {
        val queue = LightingUpdates()
        queue.offer(update(10))
        assertEquals(update(10), queue.take())
        (11..90).forEach { queue.offer(update(it)) }
        assertNull(queue.take())
        queue.finish()
        assertEquals(update(90), queue.take())
        queue.finish()
        assertNull(queue.take())
    }

    @Test fun releaseRepeatsTheFinalPositionEvenIfItWasJustPreviewed() {
        val queue = LightingUpdates()
        queue.offer(update(75))
        queue.take()
        queue.offer(update(75, preview = false))
        queue.offer(update(75)) // A late preview must not erase the final reliable send.
        queue.finish()
        assertEquals(update(75, preview = false), queue.take())
    }

    @Test fun returningToTheActiveValueDiscardsAnObsoleteWaitingPosition() {
        val queue = LightingUpdates()
        queue.offer(update(20)); queue.take()
        queue.offer(update(80)); queue.offer(update(20))
        queue.finish()
        assertNull(queue.take())
    }

    @Test fun cancellingOrChangingModesDoesNotLeaveStaleCommands() {
        val queue = LightingUpdates()
        queue.offer(update(20)); queue.take(); queue.offer(update(90))
        queue.discardPending()
        assertEquals(update(20), queue.active)
        queue.finish()
        assertNull(queue.take())
        queue.offer(update(50)); queue.take(); queue.offer(update(60))
        queue.clear()
        assertNull(queue.active)
        assertNull(queue.take())
    }
}
