package com.pokemontcgscanner.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VariantIdentityTest {
    private val normal = CardVariant("card::normal::0", "card", "normal-source", "Normal")
    private val stampedNormal = CardVariant("card::normal::1", "card", "stamped-source", "Normal")
    private val reverse = CardVariant("card::reverse::2", "card", "reverse-source", "Reverse Holo")
    private val unclassified = CardVariant(
        "card::unclassified-physical", "card", "unclassified-physical", "Finish not catalogued",
        type = "unclassified", evidenceStatus = "unclassified", provenanceSource = "tcgdex-card-existence"
    )

    @Test fun `unique legacy display resolves to stable catalogue id`() {
        val result = VariantIdentity.reconcile("Reverse Holo", listOf(normal, reverse))
        assertEquals(reverse, (result as LegacyVariantDecision.Resolved).variant)
    }

    @Test fun `ambiguous display is never guessed or merged`() {
        assertTrue(VariantIdentity.reconcile("Normal", listOf(normal, stampedNormal)) is LegacyVariantDecision.Ambiguous)
    }

    @Test fun `unknown and unmatched displays remain reviewable`() {
        assertTrue(VariantIdentity.reconcile("Unknown", listOf(normal)) is LegacyVariantDecision.Unmatched)
        assertTrue(VariantIdentity.reconcile("Galaxy foil", listOf(normal)) is LegacyVariantDecision.Unmatched)
    }

    @Test fun `confirmation requires an explicit stable id`() {
        assertNull(AllocationConfirmation.create("card", null, listOf(normal, reverse), 1, 3))
        assertNull(AllocationConfirmation.create("card", "made-up", listOf(normal), 1, 3))
    }

    @Test fun `same display variants produce separate allocation identity`() {
        val first = AllocationConfirmation.create("card", normal.id, listOf(normal, stampedNormal), 1, 3)!!
        val second = AllocationConfirmation.create("card", stampedNormal.id, listOf(normal, stampedNormal), 1, 3)!!
        assertEquals(first.variantDisplayName, second.variantDisplayName)
        assertTrue(first.variantId != second.variantId)
    }

    @Test fun `normal and review flows share equivalent confirmation data`() {
        val normalFlow = AllocationConfirmation.create("card", reverse.id, listOf(normal, reverse), 2, 9)
        val reviewFlow = AllocationConfirmation.create("card", reverse.id, listOf(normal, reverse), 2, 9)
        assertEquals(normalFlow, reviewFlow)
    }

    @Test fun `unclassified finish is never auto selected or used for legacy reconciliation`() {
        assertNull(AllocationConfirmation.defaultVariantSelection(listOf(unclassified)))
        assertTrue(
            VariantIdentity.reconcile("Finish not catalogued", listOf(unclassified)) is LegacyVariantDecision.Unmatched
        )
    }

    @Test fun `explicit unclassified confirmation remains reviewable`() {
        val confirmation = AllocationConfirmation.create("card", unclassified.id, listOf(unclassified), 2, 9)!!
        assertEquals(VariantResolutionState.UNCLASSIFIED, confirmation.variantResolution)
    }
}
