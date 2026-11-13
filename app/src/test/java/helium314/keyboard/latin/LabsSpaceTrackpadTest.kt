// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LabsSpaceTrackpadTest {
    @Test
    fun spaceTimeoutUsesTrackpadOnlyForPopupFreeSpace() {
        assertEquals(350, LabsSpaceTrackpad.spaceTimeout(300, true, false))
        assertEquals(450, LabsSpaceTrackpad.spaceTimeout(300, true, true))
        assertEquals(450, LabsSpaceTrackpad.spaceTimeout(300, false, false))
        assertEquals(600, LabsSpaceTrackpad.spaceTimeout(400, false, true))
    }

    @Test
    fun slopIsCircular() {
        assertTrue(LabsSpaceTrackpad.isWithinSlop(0, 0, 10))
        assertTrue(LabsSpaceTrackpad.isWithinSlop(10, 0, 10))
        assertTrue(LabsSpaceTrackpad.isWithinSlop(6, 8, 10))
        assertFalse(LabsSpaceTrackpad.isWithinSlop(7, 8, 10))
        assertFalse(LabsSpaceTrackpad.isWithinSlop(-11, 0, 10))
    }

    @Test
    fun stepAccumulatorEmitsWholeStepsAndKeepsRemainders() {
        val steps = LabsTrackpadSteps(22, 55, 1_000_000f)
        assertEquals(LabsTrackpadSteps.Steps(0, 0), steps.add(21, 0))
        assertEquals(LabsTrackpadSteps.Steps(1, 0), steps.add(1, 0))
        assertEquals(LabsTrackpadSteps.Steps(-2, 0), steps.add(-44, 0))
        assertEquals(LabsTrackpadSteps.Steps(0, 1), steps.add(0, 55))
    }

    @Test
    fun accelerationShortensTheDistanceToTheNextStep() {
        val steps = LabsTrackpadSteps(22, 55, 50f)
        assertEquals(1, steps.add(22, 0).horizontal)
        assertEquals(2, steps.add(0, 55).vertical)
    }
}
