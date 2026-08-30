package com.pokemontcgscanner.app.data

sealed interface LegacyVariantDecision {
    data class Resolved(val variant: CardVariant) : LegacyVariantDecision
    data object Ambiguous : LegacyVariantDecision
    data object Unmatched : LegacyVariantDecision
}

object VariantIdentity {
    fun reconcile(legacyDisplayName: String, variants: List<CardVariant>): LegacyVariantDecision {
        val legacy = legacyDisplayName.trim()
        if (legacy.isBlank() || legacy.equals("Unknown", ignoreCase = true)) return LegacyVariantDecision.Unmatched
        val matches = variants.filter {
            !it.isUnclassified && it.displayName.trim().equals(legacy, ignoreCase = true)
        }.distinctBy { it.id }
        return when (matches.size) {
            1 -> LegacyVariantDecision.Resolved(matches.single())
            0 -> LegacyVariantDecision.Unmatched
            else -> LegacyVariantDecision.Ambiguous
        }
    }
}

data class ConfirmedAllocation(
    val cardId: String,
    val variantId: String,
    val variantDisplayName: String,
    val quantity: Int,
    val locationId: Long,
    val variantResolution: String = VariantResolutionState.RESOLVED
)

object AllocationConfirmation {
    fun defaultVariantSelection(variants: List<CardVariant>): String? =
        variants.singleOrNull()?.takeUnless { it.isUnclassified }?.id

    fun create(
        cardId: String,
        selectedVariantId: String?,
        variants: List<CardVariant>,
        quantity: Int,
        locationId: Long
    ): ConfirmedAllocation? {
        if (selectedVariantId.isNullOrBlank() || quantity < 1 || locationId <= 0) return null
        val selected = variants.singleOrNull { it.id == selectedVariantId && it.cardId == cardId } ?: return null
        return ConfirmedAllocation(
            cardId, selected.id, selected.displayName, quantity, locationId,
            if (selected.isUnclassified) VariantResolutionState.UNCLASSIFIED else VariantResolutionState.RESOLVED
        )
    }
}
