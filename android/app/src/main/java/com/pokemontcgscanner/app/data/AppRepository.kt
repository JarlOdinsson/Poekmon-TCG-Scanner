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
        cards.count()
        reconcileLegacyAllocations()
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
    suspend fun variantsFor(cardId: String): List<CardVariant> = cards.variantsFor(cardId)

    suspend fun createLocation(name: String, type: String): Long =
        collection.insertLocation(LocationEntity(name = name.trim(), type = type))

    suspend fun startSession(name: String, destinationId: Long): Long =
        collection.insertSession(ScanSessionEntity(name = name, destinationId = destinationId))

    suspend fun endSession(id: Long) = collection.endSession(id, System.currentTimeMillis())

    suspend fun confirmAllocation(
        confirmation: ConfirmedAllocation,
        status: String,
        sessionId: Long?,
        reviewItemId: Long?
    ) = collectionDb.withTransaction {
        val existing = collection.resolvedAllocation(
            confirmation.cardId, confirmation.variantId, confirmation.locationId, status
        )
        if (existing == null) {
            collection.insertAllocation(
                AllocationEntity(
                    cardId = confirmation.cardId,
                    variantId = confirmation.variantId,
                    variantDisplayName = confirmation.variantDisplayName,
                    locationId = confirmation.locationId,
                    status = status,
                    quantity = confirmation.quantity
                )
            )
        } else {
            collection.updateAllocation(
                existing.copy(
                    variantDisplayName = confirmation.variantDisplayName,
                    quantity = existing.quantity + confirmation.quantity,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
        if (sessionId != null) {
            collection.insertEvent(
                ScanEventEntity(
                    sessionId = sessionId,
                    cardId = confirmation.cardId,
                    variantId = confirmation.variantId,
                    variantDisplayName = confirmation.variantDisplayName,
                    quantity = confirmation.quantity,
                    confidence = 1f
                )
            )
        }
        if (reviewItemId != null) collection.resolveReviewItem(reviewItemId)
    }

    suspend fun queueReview(sessionId: Long?, imagePath: String, candidates: List<String>) {
        collection.insertReviewItem(
            ReviewItemEntity(
                sessionId = sessionId,
                imagePath = imagePath,
                candidateIds = candidates.joinToString(","),
                note = "Needs exact printing and variant confirmation"
            )
        )
    }

    suspend fun resolveAllocationVariant(allocationId: Long, variant: CardVariant) {
        collectionDb.withTransaction {
            val source = collection.allocationById(allocationId) ?: return@withTransaction
            if (source.cardId != variant.cardId || source.variantId != null) return@withTransaction
            mergeOrResolve(source, variant)
        }
    }

    private suspend fun reconcileLegacyAllocations() {
        collection.unresolvedAllocations().forEach { legacy ->
            val decision = VariantIdentity.reconcile(legacy.variantDisplayName, cards.variantsFor(legacy.cardId))
            collectionDb.withTransaction {
                val current = collection.allocationById(legacy.id) ?: return@withTransaction
                if (current.variantId != null) return@withTransaction
                when (decision) {
                    is LegacyVariantDecision.Resolved -> mergeOrResolve(current, decision.variant)
                    LegacyVariantDecision.Ambiguous -> collection.updateAllocation(
                        current.copy(variantResolution = VariantResolutionState.AMBIGUOUS, updatedAt = System.currentTimeMillis())
                    )
                    LegacyVariantDecision.Unmatched -> collection.updateAllocation(
                        current.copy(variantResolution = VariantResolutionState.UNMATCHED, updatedAt = System.currentTimeMillis())
                    )
                }
            }
        }
    }

    private suspend fun mergeOrResolve(source: AllocationEntity, variant: CardVariant) {
        val destination = collection.resolvedAllocation(source.cardId, variant.id, source.locationId, source.status)
        if (destination == null || destination.id == source.id) {
            collection.updateAllocation(
                source.copy(
                    variantId = variant.id,
                    variantDisplayName = variant.displayName,
                    variantResolution = VariantResolutionState.RESOLVED,
                    updatedAt = System.currentTimeMillis()
                )
            )
        } else {
            collection.updateAllocation(
                destination.copy(
                    quantity = destination.quantity + source.quantity,
                    variantDisplayName = variant.displayName,
                    updatedAt = System.currentTimeMillis()
                )
            )
            collection.deleteAllocation(source.id)
        }
    }

    suspend fun moveAllocation(allocationId: Long, quantity: Int, destinationId: Long) {
        collectionDb.withTransaction {
            val source = collection.allocationById(allocationId) ?: return@withTransaction
            if (source.locationId == destinationId) return@withTransaction
            val moving = quantity.coerceIn(1, source.quantity)
            // Unresolved rows deliberately never coalesce by their legacy display label.
            // A label is presentation/reconciliation input, not a variant identity.
            val destination = source.variantId?.let {
                collection.resolvedAllocation(source.cardId, it, destinationId, source.status)
            }
            if (destination == null) {
                collection.insertAllocation(
                    source.copy(id = 0, locationId = destinationId, quantity = moving, updatedAt = System.currentTimeMillis())
                )
            } else {
                collection.updateAllocation(
                    destination.copy(quantity = destination.quantity + moving, updatedAt = System.currentTimeMillis())
                )
            }
            if (moving == source.quantity) collection.deleteAllocation(source.id)
            else collection.updateAllocation(
                source.copy(quantity = source.quantity - moving, updatedAt = System.currentTimeMillis())
            )
        }
    }
}
