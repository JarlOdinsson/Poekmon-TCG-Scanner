package com.pokemontcgscanner.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pokemontcgscanner.app.data.AllocationEntity
import com.pokemontcgscanner.app.data.AppRepository
import com.pokemontcgscanner.app.data.CardEntity
import com.pokemontcgscanner.app.data.CardVariant
import com.pokemontcgscanner.app.data.ConfirmedAllocation
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
import java.io.InputStream

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

    fun updateLocation(id: Long, name: String, type: String) = viewModelScope.launch {
        repository.updateLocation(id, name, type)
    }

    fun startSession(name: String, destination: Long) = viewModelScope.launch {
        repository.startSession(name.ifBlank { "Quick Scan" }, destination)
    }

    fun endSession(session: ScanSessionEntity) = viewModelScope.launch { repository.endSession(session.id) }

    suspend fun variantsFor(cardId: String): List<CardVariant> = repository.variantsFor(cardId)

    fun confirmAllocation(
        confirmation: ConfirmedAllocation,
        status: String = "AVAILABLE",
        sessionId: Long? = activeSession.value?.id,
        reviewItemId: Long? = null,
        imagePath: String? = null,
        confidence: Float = 1f,
        onDone: () -> Unit = {}
    ) = viewModelScope.launch {
        repository.confirmAllocation(confirmation, status, sessionId, reviewItemId, confidence)
        if (reviewItemId != null && imagePath != null) runCatching { File(imagePath).delete() }
        onDone()
    }

    fun queueReview(imagePath: String, candidates: List<CardEntity>, onDone: () -> Unit = {}) = viewModelScope.launch {
        repository.queueReview(activeSession.value?.id, imagePath, candidates.map { it.id })
        onDone()
    }

    fun resolveAllocationVariant(allocationId: Long, variant: CardVariant) = viewModelScope.launch {
        repository.resolveAllocationVariant(allocationId, variant)
    }

    fun moveAllocation(allocationId: Long, quantity: Int, destinationId: Long) = viewModelScope.launch {
        repository.moveAllocation(allocationId, quantity, destinationId)
    }

    fun setAllocationQuantity(allocationId: Long, quantity: Int) = viewModelScope.launch {
        repository.setAllocationQuantity(allocationId, quantity)
    }

    fun changeAllocationStatus(allocationId: Long, status: String) = viewModelScope.launch {
        repository.changeAllocationStatus(allocationId, status)
    }

    fun editAllocation(allocationId: Long, quantity: Int, status: String) = viewModelScope.launch {
        repository.editAllocation(allocationId, quantity, status)
    }

    fun createBackup(onResult: (Result<String>) -> Unit) = viewModelScope.launch {
        onResult(runCatching { repository.createBackup() })
    }

    fun restoreBackup(raw: String, onResult: (Result<Unit>) -> Unit) = viewModelScope.launch {
        onResult(runCatching { repository.restoreBackup(raw) })
    }

    fun installCatalogUpdate(input: InputStream, onResult: (Result<com.pokemontcgscanner.app.data.CatalogUpdateResult>) -> Unit) = viewModelScope.launch {
        onResult(runCatching { input.use { repository.installCatalogUpdate(it) } })
    }
}

data class CollectionSnapshot(
    val cards: List<CardEntity>,
    val totals: List<InventoryTotal>,
    val allocations: List<AllocationEntity>,
    val locations: List<LocationEntity>
)
