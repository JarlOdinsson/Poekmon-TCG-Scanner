package com.pokemontcgscanner.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionBackupTest {
    private val location = LocationEntity(1, "Main Binder", "BINDER", 42)
    private val allocation = AllocationEntity(
        2, "card-1", "card-1::normal", "Normal", VariantResolutionState.RESOLVED,
        1, "AVAILABLE", 3, 100
    )
    private val session = ScanSessionEntity(3, "Session", 1, 200, 300)
    private val event = ScanEventEntity(4, 3, "card-1", "card-1::normal", "Normal", 1, .82f, 250)
    private val review = ReviewItemEntity(5, 3, "private/path.jpg", "card-1", "Review", false, 260)

    @Test fun `backup round trip preserves collection identity and history`() {
        val original = CollectionBackupSnapshot(2, listOf(location), listOf(allocation), listOf(session), listOf(event), listOf(review))
        val restored = CollectionBackupCodec.decode(CollectionBackupCodec.encode(original))
        assertEquals(original.catalogVersion, restored.catalogVersion)
        assertEquals(original.locations, restored.locations)
        assertEquals(original.allocations, restored.allocations)
        assertEquals(original.sessions, restored.sessions)
        assertEquals(original.events, restored.events)
        assertEquals("", restored.reviewItems.single().imagePath)
        assertTrue(restored.reviewItems.single().note.contains("image is not included"))
    }

    @Test fun `restore rejects missing location references before mutation`() {
        val invalid = CollectionBackupSnapshot(2, listOf(location), listOf(allocation.copy(locationId = 99)), emptyList(), emptyList(), emptyList())
        val raw = CollectionBackupCodec.encode(invalid)
        assertThrows(IllegalArgumentException::class.java) { CollectionBackupCodec.decode(raw) }
    }

    @Test fun `restore rejects non CardDex JSON`() {
        assertThrows(IllegalArgumentException::class.java) { CollectionBackupCodec.decode("{\"hello\":\"world\"}") }
    }
}
