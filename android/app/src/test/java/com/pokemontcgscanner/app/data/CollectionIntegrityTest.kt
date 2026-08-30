package com.pokemontcgscanner.app.data

import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionIntegrityTest {
    private val location = LocationEntity(1, "Binder", "BINDER")
    private val allocation = AllocationEntity(1, "card", "variant", "Normal", VariantResolutionState.RESOLVED, 1, "AVAILABLE", 2)
    private val session = ScanSessionEntity(1, "Scan", 1)
    private val event = ScanEventEntity(1, 1, "card", "variant", "Normal", 1, .8f)

    @Test fun `valid graph passes integrity audit`() {
        assertTrue(CollectionIntegrity.audit(listOf(location), listOf(allocation), listOf(session), listOf(event), emptyList()).isEmpty())
    }

    @Test fun `orphan quantities and identity contradictions are all reported`() {
        val issues = CollectionIntegrity.audit(
            emptyList(),
            listOf(allocation.copy(variantId = null, quantity = 0)),
            listOf(session),
            listOf(event.copy(sessionId = 99, confidence = 2f)),
            listOf(ReviewItemEntity(1, 99, "", "", "review"))
        )
        assertTrue(issues.size >= 5)
        assertTrue(issues.any { it.contains("stable variant ID") })
    }

    @Test fun `duplicate stable identity is detected before quantities diverge`() {
        val issues = CollectionIntegrity.audit(
            listOf(location), listOf(allocation, allocation.copy(id = 2)), emptyList(), emptyList(), emptyList()
        )
        assertTrue(issues.any { it.contains("duplicate resolved") })
    }
}
