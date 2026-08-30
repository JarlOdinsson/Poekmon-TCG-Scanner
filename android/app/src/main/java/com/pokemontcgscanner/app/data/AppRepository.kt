package com.pokemontcgscanner.app.data

import android.content.Context
import java.io.InputStream
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
        assertCollectionIntegrity()
    }

    suspend fun search(query: String): List<CardEntity> = cards.search(query.trim())
    suspend fun variantsFor(cardId: String): List<CardVariant> = cards.variantsFor(cardId)

    suspend fun createLocation(name: String, type: String): Long =
        collection.insertLocation(LocationEntity(name = name.trim(), type = type))

    suspend fun updateLocation(id: Long, name: String, type: String) {
        if (id > 0 && name.isNotBlank()) collection.updateLocation(id, name.trim(), type)
    }

    suspend fun startSession(name: String, destinationId: Long): Long =
        collection.insertSession(ScanSessionEntity(name = name, destinationId = destinationId))

    suspend fun endSession(id: Long) = collection.endSession(id, System.currentTimeMillis())

    suspend fun confirmAllocation(
        confirmation: ConfirmedAllocation,
        status: String,
        sessionId: Long?,
        reviewItemId: Long?,
        confidence: Float = 1f
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
                    variantResolution = confirmation.variantResolution,
                    locationId = confirmation.locationId,
                    status = status,
                    quantity = confirmation.quantity
                )
            )
        } else {
            collection.updateAllocation(
                existing.copy(
                    variantDisplayName = confirmation.variantDisplayName,
                    variantResolution = confirmation.variantResolution,
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
                    confidence = confidence.coerceIn(0f, 1f)
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
            if (source.cardId != variant.cardId || variant.isUnclassified) return@withTransaction
            if (source.variantId != null && source.variantResolution != VariantResolutionState.UNCLASSIFIED) {
                return@withTransaction
            }
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
        collection.unresolvedEvents().forEach { legacy ->
            val decision = VariantIdentity.reconcile(legacy.variantDisplayName, cards.variantsFor(legacy.cardId))
            if (decision is LegacyVariantDecision.Resolved) {
                collection.updateEvent(
                    legacy.copy(
                        variantId = decision.variant.id,
                        variantDisplayName = decision.variant.displayName
                    )
                )
            }
        }
    }

    private suspend fun assertCollectionIntegrity() {
        val issues = CollectionIntegrity.audit(
            collection.allLocations(), collection.allAllocations(), collection.allSessions(),
            collection.allEvents(), collection.allReviewItems()
        )
        check(issues.isEmpty()) { "Collection integrity check failed: ${issues.joinToString()}" }
    }

    private suspend fun mergeOrResolve(source: AllocationEntity, variant: CardVariant) {
        val destination = collection.resolvedAllocation(source.cardId, variant.id, source.locationId, source.status)
        if (destination == null || destination.id == source.id) {
            collection.updateAllocation(
                source.copy(
                    variantId = variant.id,
                    variantDisplayName = variant.displayName,
                    variantResolution = if (variant.isUnclassified) {
                        VariantResolutionState.UNCLASSIFIED
                    } else {
                        VariantResolutionState.RESOLVED
                    },
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

    suspend fun setAllocationQuantity(allocationId: Long, quantity: Int) {
        collectionDb.withTransaction {
            val source = collection.allocationById(allocationId) ?: return@withTransaction
            if (quantity <= 0) collection.deleteAllocation(source.id)
            else collection.updateAllocation(source.copy(quantity = quantity, updatedAt = System.currentTimeMillis()))
        }
    }

    suspend fun changeAllocationStatus(allocationId: Long, status: String) {
        collectionDb.withTransaction {
            val source = collection.allocationById(allocationId) ?: return@withTransaction
            if (source.status == status) return@withTransaction
            val destination = source.variantId?.let {
                collection.resolvedAllocation(source.cardId, it, source.locationId, status)
            }
            if (destination == null) {
                collection.updateAllocation(source.copy(status = status, updatedAt = System.currentTimeMillis()))
            } else {
                collection.updateAllocation(
                    destination.copy(
                        quantity = destination.quantity + source.quantity,
                        updatedAt = System.currentTimeMillis()
                    )
                )
                collection.deleteAllocation(source.id)
            }
        }
    }

    suspend fun editAllocation(allocationId: Long, quantity: Int, status: String) {
        collectionDb.withTransaction {
            val source = collection.allocationById(allocationId) ?: return@withTransaction
            val destination = if (source.status != status) source.variantId?.let {
                collection.resolvedAllocation(source.cardId, it, source.locationId, status)
            } else null
            when (val decision = CollectionEditing.plan(quantity, status, destination?.id)) {
                AllocationEditDecision.Remove -> collection.deleteAllocation(source.id)
                is AllocationEditDecision.Update -> collection.updateAllocation(
                    source.copy(
                        quantity = decision.quantity,
                        status = decision.status,
                        updatedAt = System.currentTimeMillis()
                    )
                )
                is AllocationEditDecision.Merge -> {
                    val target = destination?.takeIf { it.id == decision.destinationId }
                        ?: return@withTransaction
                    collection.updateAllocation(
                        target.copy(
                            quantity = target.quantity + decision.quantityToAdd,
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                    collection.deleteAllocation(source.id)
                }
            }
        }
    }

    suspend fun createBackup(): String = collectionDb.withTransaction {
        CollectionBackupCodec.encode(
            CollectionBackupSnapshot(
                catalogVersion = cards.catalogVersion(),
                locations = collection.allLocations(),
                allocations = collection.allAllocations(),
                sessions = collection.allSessions(),
                events = collection.allEvents(),
                reviewItems = collection.allReviewItems()
            )
        )
    }

    suspend fun restoreBackup(raw: String) {
        val backup = CollectionBackupCodec.decode(raw)
        require(backup.catalogVersion <= cards.catalogVersion()) {
            "Backup requires a newer catalogue version (${backup.catalogVersion})"
        }
        val identities = cards.identities()
        require(backup.allocations.all { it.cardId in identities.cardIds }) {
            "Backup contains a collection card that is absent from this catalogue"
        }
        require(backup.allocations.all { it.variantId == null || it.variantId in identities.variantIds }) {
            "Backup contains a variant that is absent from this catalogue"
        }
        require(backup.events.all { it.cardId in identities.cardIds }) {
            "Backup contains scan history for a card absent from this catalogue"
        }
        require(backup.events.all { it.variantId == null || it.variantId in identities.variantIds }) {
            "Backup contains scan history for a variant absent from this catalogue"
        }
        collectionDb.withTransaction {
            collection.clearReviewItems()
            collection.clearEvents()
            collection.clearSessions()
            collection.clearAllocations()
            collection.clearLocations()
            backup.locations.forEach { collection.insertLocation(it) }
            backup.allocations.forEach { collection.insertAllocation(it) }
            backup.sessions.forEach { collection.insertSession(it) }
            backup.events.forEach { collection.insertEvent(it) }
            backup.reviewItems.forEach { collection.insertReviewItem(it) }
        }
    }

    suspend fun installCatalogUpdate(input: InputStream): CatalogUpdateResult = cards.installUpdate(input)
}
