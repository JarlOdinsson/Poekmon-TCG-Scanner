package com.pokemontcgscanner.app.data

sealed interface AllocationEditDecision {
    data object Remove : AllocationEditDecision
    data class Update(val quantity: Int, val status: String) : AllocationEditDecision
    data class Merge(val destinationId: Long, val quantityToAdd: Int) : AllocationEditDecision
}

object CollectionEditing {
    fun plan(quantity: Int, status: String, destinationId: Long?): AllocationEditDecision = when {
        quantity <= 0 -> AllocationEditDecision.Remove
        destinationId != null -> AllocationEditDecision.Merge(destinationId, quantity)
        else -> AllocationEditDecision.Update(quantity, status)
    }
}
