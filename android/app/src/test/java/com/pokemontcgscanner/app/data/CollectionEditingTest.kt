package com.pokemontcgscanner.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionEditingTest {
    @Test fun `zero quantity removes the entry even if a merge target exists`() {
        assertTrue(CollectionEditing.plan(0, "TRADE", 9) is AllocationEditDecision.Remove)
    }

    @Test fun `quantity and status update atomically when no target collides`() {
        assertEquals(
            AllocationEditDecision.Update(4, "DECK"),
            CollectionEditing.plan(4, "DECK", null)
        )
    }

    @Test fun `status collision merges the requested quantity into stable identity target`() {
        assertEquals(
            AllocationEditDecision.Merge(12, 3),
            CollectionEditing.plan(3, "TRADE", 12)
        )
    }
}
