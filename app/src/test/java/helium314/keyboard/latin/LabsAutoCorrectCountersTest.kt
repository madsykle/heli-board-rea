// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LabsAutoCorrectCountersTest {
    @Test
    fun onlySeparatorAutoCorrectCounts() {
        assertTrue(
            isAutoCorrectEvent(
                LastComposedWord.COMMIT_TYPE_DECIDED_WORD, "helo", "hello", true
            )
        )
        assertFalse(
            isAutoCorrectEvent(
                LastComposedWord.COMMIT_TYPE_DECIDED_WORD, "hello", "hello", true
            )
        )
        assertFalse(
            isAutoCorrectEvent(
                LastComposedWord.COMMIT_TYPE_MANUAL_PICK, "helo", "hello", true
            )
        )
        assertFalse(
            isAutoCorrectEvent(
                LastComposedWord.COMMIT_TYPE_DECIDED_WORD, "helo", "hello", false
            )
        )
        assertFalse(
            isAutoCorrectEvent(
                LastComposedWord.COMMIT_TYPE_DECIDED_WORD, "", "hello", true
            )
        )
    }

    @Test
    fun opinionClassification() {
        assertEquals(AutoCorrectOpinion.NO_OPINION, classifyOpinion(null, "hello"))
        assertEquals(AutoCorrectOpinion.MATCH, classifyOpinion("hello", "hello"))
        assertEquals(AutoCorrectOpinion.MATCH, classifyOpinion("hello", "Hello"))
        assertEquals(AutoCorrectOpinion.DIFFER, classifyOpinion("help", "hello"))
    }
}
