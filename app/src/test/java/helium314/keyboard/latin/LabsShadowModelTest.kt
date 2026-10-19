// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import java.io.StringReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LabsShadowModelTest {
    private val sample = """
        v1
        #U
        a	10
        ab	5
        abc	4
        b	3
        #B
        <s>	a	5
        a	b	4
        ab	c	3
        #T
        <s>	a	b	4
        a	b	c	3
        #END	100	0.75
    """.trimIndent() + "\n"

    @Test
    fun parsesAndPredictsWithBigramBackoff() {
        val parsed = assertNotNull(
            ShadowModelParser.parse(StringReader(sample).buffered())
        )
        assertEquals(14, parsed.lineCount)
        assertEquals(100L, parsed.model.totalTokens)
        assertEquals("ab", parsed.model.predict("a", listOf("a")))
    }

    @Test
    fun parsesAndPredictsWithTrigramContext() {
        val parsed = assertNotNull(
            ShadowModelParser.parse(StringReader(sample).buffered())
        )
        assertEquals("abc", parsed.model.predict("a", listOf("a", "b")))
    }

    @Test
    fun ignoresContextsWithoutAPreviousWord() {
        val parsed = assertNotNull(
            ShadowModelParser.parse(StringReader(sample).buffered())
        )
        assertNull(parsed.model.predict("a", emptyList()))
    }

    @Test
    fun rejectsWrongHeader() {
        assertNull(
            ShadowModelParser.parse(
                StringReader(sample.replaceFirst("v1", "v2")).buffered()
            )
        )
    }

    @Test
    fun rejectsMissingEndLine() {
        assertNull(
            ShadowModelParser.parse(
                StringReader(sample.substringBefore("#END")).buffered()
            )
        )
    }

    @Test
    fun rejectsTrailingData() {
        assertNull(
            ShadowModelParser.parse(
                StringReader(sample + "a\t1\n").buffered()
            )
        )
    }
}
