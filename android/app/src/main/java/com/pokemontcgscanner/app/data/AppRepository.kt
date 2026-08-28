package com.pokemontcgscanner.app.data

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow

class AppRepository(context: Context) {
    private val catalogDb = CatalogDatabase.create(context)
    private val collectionDb = CollectionDatabase.create(context)
    private val cards = catalogDb.cards()
    private val collection = collectionDb.collection()

    val catalog: Flow<List<CardEntity>> = cards.observeAll()
    val totals: Flow<List<InventoryTotal>> = collection.observeTotals()
    val allocations: Flow<List<AllocationEntity>> = collection.observeAllocations()
    val locations: Flow<List<LocationEntity>> = collection.observeLocations()
    val activeSession: Flow<ScanSessionEntity?> = collection.observeActiveSession()
    val reviewItems: Flow<List<ReviewItemEntity>> = collection.observeReviewItems()
    val sessionSummaries: Flow<List<SessionSummary>> = collection.observeSessionSummaries()

    suspend fun initialize() {
        // Forces version/checksum installation of the read-only catalogue asset.
        // This never opens or migrates the separate user collection database.
        cards.count()
        if (collection.locationCount() == 0) {
            listOf(
                LocationEntity(name = "Unassigned", type = "UNASSIGNED", color = 0xFF7E8799),
                LocationEntity(name = "Main Collection", type = "COLLECTION", color = 0xFF5B8CFF),
                LocationEntity(name = "Trade Binder", type = "BINDER", color = 0xFFB65CFF),
                LocationEntity(name = "Bulk Box 1", type = "STORAGE", color = 0xFFFFB547)
            ).forEach { collection.insertLocation(it) }
        }
    }

    suspend fun search(query: String): List<CardEntity> = cards.search(query.trim())

    suspend fun createLocation(name: String, type: String): Long =
        collection.insertLocation(LocationEntity(name = name.trim(), type = type))

    suspend fun startSession(name: String, destinationId: Long): Long =
        collection.insertSession(ScanSessionEntity(name = name, destinationId = destinationId))

    suspend fun endSession(id: Long) = collection.endSession(id, System.currentTimeMillis())

    suspend fun addCard(cardId: String, variant: String, locationId: Long, status: String, quantity: Int, sessionId: Long?) {
        val old = collection.allocation(cardId, variant, locationId, status)
        collection.upsertAllocation(
            old?.copy(quantity = old.quantity + quantity, updatedAt = System.currentTimeMillis())
                ?: AllocationEntity(cardId = cardId, variant = variant, locationId = locationId, status = status, quantity = quantity)
        )
        if (sessionId != null) {
            collection.insertEvent(ScanEventEntity(sessionId = sessionId, cardId = cardId, variant = variant, quantity = quantity, confidence = 1f))
        }
    }

    suspend fun queueReview(sessionId: Long?, imagePath: String, candidates: List<String>) {
        collection.insertReviewItem(
            ReviewItemEntity(sessionId = sessionId, imagePath = imagePath, candidateIds = candidates.joinToString(","), note = "Needs exact printing confirmation")
        )
    }

    suspend fun resolveReview(itemId: Long) = collection.resolveReviewItem(itemId)

    suspend fun moveAllocation(allocationId: Long, quantity: Int, destinationId: Long) {
        collectionDb.withTransaction {
            val source = collection.allocationById(allocationId) ?: return@withTransaction
            if (source.locationId == destinationId) return@withTransaction
            val moving = quantity.coerceIn(1, source.quantity)
            val destination = collection.allocation(source.cardId, source.variant, destinationId, source.status)
            collection.upsertAllocation(
                destination?.copy(quantity = destination.quantity + moving, updatedAt = System.currentTimeMillis())
                    ?: source.copy(id = 0, locationId = destinationId, quantity = moving, updatedAt = System.currentTimeMillis())
            )
            if (moving == source.quantity) collection.deleteAllocation(source.id)
            else collection.upsertAllocation(source.copy(quantity = source.quantity - moving, updatedAt = System.currentTimeMillis()))
        }
    }

    suspend fun defaultLocation(): LocationEntity? = collection.firstLocation()
}
