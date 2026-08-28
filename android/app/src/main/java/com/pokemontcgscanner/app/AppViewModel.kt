package com.pokemontcgscanner.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pokemontcgscanner.app.data.AllocationEntity
import com.pokemontcgscanner.app.data.AppRepository
import com.pokemontcgscanner.app.data.CardEntity
import com.pokemontcgscanner.app.data.InventoryTotal
import com.pokemontcgscanner.app.data.LocationEntity
import com.pokemontcgscanner.app.data.ScanSessionEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = AppRepository(application)
    val query = MutableStateFlow("")
    val catalog = repository.catalog.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val totals = repository.totals.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val allocations = repository.allocations.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val locations = repository.locations.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val activeSession = repository.activeSession.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val reviewItems = repository.reviewItems.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val sessionSummaries = repository.sessionSummaries.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val visibleCards: StateFlow<List<CardEntity>> = query.debounce(150).flatMapLatest { text ->
        if (text.isBlank()) catalog else flow { emit(repository.search(text)) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init { viewModelScope.launch { repository.initialize() } }

    fun setQuery(value: String) { query.value = value }

    fun createLocation(name: String, type: String) = viewModelScope.launch {
        if (name.isNotBlank()) repository.createLocation(name, type)
    }

    fun startSession(name: String, destination: Long) = viewModelScope.launch {
        repository.startSession(name.ifBlank { "Quick Scan" }, destination)
    }

    fun endSession(session: ScanSessionEntity) = viewModelScope.launch { repository.endSession(session.id) }

    fun addCard(card: CardEntity, variant: String, quantity: Int, location: Long, status: String = "AVAILABLE", onDone: () -> Unit = {}) =
        viewModelScope.launch {
            repository.addCard(card.id, variant, location, status, quantity.coerceAtLeast(1), activeSession.value?.id)
            onDone()
        }

    fun queueReview(imagePath: String, candidates: List<CardEntity>, onDone: () -> Unit = {}) = viewModelScope.launch {
        repository.queueReview(activeSession.value?.id, imagePath, candidates.map { it.id })
        onDone()
    }

    fun resolveReview(itemId: Long, imagePath: String, card: CardEntity, location: Long, onDone: () -> Unit = {}) = viewModelScope.launch {
        repository.addCard(card.id, card.variants.substringBefore(','), location, "AVAILABLE", 1, activeSession.value?.id)
        repository.resolveReview(itemId)
        runCatching { File(imagePath).delete() }
        onDone()
    }

    fun moveAllocation(allocationId: Long, quantity: Int, destinationId: Long) = viewModelScope.launch {
        repository.moveAllocation(allocationId, quantity, destinationId)
    }
}

data class CollectionSnapshot(
    val cards: List<CardEntity>,
    val totals: List<InventoryTotal>,
    val allocations: List<AllocationEntity>,
    val locations: List<LocationEntity>
)
