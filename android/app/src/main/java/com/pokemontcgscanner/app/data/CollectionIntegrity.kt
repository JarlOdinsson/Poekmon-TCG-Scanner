package com.pokemontcgscanner.app.data

object CollectionIntegrity {
    fun audit(
        locations: List<LocationEntity>,
        allocations: List<AllocationEntity>,
        sessions: List<ScanSessionEntity>,
        events: List<ScanEventEntity>,
        reviews: List<ReviewItemEntity>
    ): List<String> = buildList {
        val locationIds = locations.map { it.id }.toSet()
        val sessionIds = sessions.map { it.id }.toSet()
        if (allocations.any { it.quantity <= 0 }) add("collection contains a non-positive quantity")
        if (events.any { it.quantity <= 0 || it.confidence !in 0f..1f }) add("scan history contains an invalid quantity or confidence")
        if (allocations.any { it.locationId !in locationIds }) add("allocation references a missing location")
        if (sessions.any { it.destinationId !in locationIds }) add("session references a missing location")
        if (events.any { it.sessionId !in sessionIds }) add("scan event references a missing session")
        if (reviews.any { it.sessionId != null && it.sessionId !in sessionIds }) add("review item references a missing session")
        if (allocations.any { it.variantResolution == VariantResolutionState.RESOLVED && it.variantId == null }) {
            add("resolved allocation is missing its stable variant ID")
        }
        if (allocations.any { it.variantResolution == VariantResolutionState.UNCLASSIFIED && it.variantId == null }) {
            add("unclassified allocation is missing its stable fallback ID")
        }
        if (allocations.any {
                it.variantResolution in setOf(
                    VariantResolutionState.PENDING, VariantResolutionState.AMBIGUOUS, VariantResolutionState.UNMATCHED
                ) && it.variantId != null
            }) add("unresolved allocation unexpectedly has a stable variant ID")
        val resolvedKeys = allocations.filter { it.variantId != null }
            .map { listOf(it.cardId, it.variantId, it.locationId.toString(), it.status) }
        if (resolvedKeys.size != resolvedKeys.toSet().size) add("duplicate resolved allocation identity")
    }
}
