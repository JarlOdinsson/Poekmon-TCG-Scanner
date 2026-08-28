package com.pokemontcgscanner.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import coil.compose.AsyncImage
import com.pokemontcgscanner.app.AppViewModel
import com.pokemontcgscanner.app.CollectionSnapshot
import com.pokemontcgscanner.app.data.CardEntity
import com.pokemontcgscanner.app.data.InventoryTotal
import com.pokemontcgscanner.app.data.LocationEntity
import com.pokemontcgscanner.app.data.AllocationEntity
import com.pokemontcgscanner.app.export.ExportService
import com.pokemontcgscanner.app.scanner.CardRecognitionEngine
import com.pokemontcgscanner.app.scanner.CardRecognitionResult
import java.io.File
import java.text.DateFormat
import java.util.Date

private data class Destination(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)
private val destinations = listOf(
    Destination("home", "Home", Icons.Default.Home), Destination("browse", "Browse", Icons.Default.GridView),
    Destination("scan", "Scan", Icons.Default.CameraAlt), Destination("storage", "Storage", Icons.Default.Inventory2)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardDexApp(vm: AppViewModel = viewModel()) {
    val nav = rememberNavController()
    val route by nav.currentBackStackEntryAsState()
    Scaffold(
        topBar = { TopAppBar(title = { Text("CardDex", fontWeight = FontWeight.ExtraBold) }, actions = { Text("OFFLINE", color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(end = 16.dp)) }) },
        bottomBar = {
            NavigationBar(modifier = Modifier.navigationBarsPadding()) {
                destinations.forEach { item ->
                    NavigationBarItem(selected = route?.destination?.route == item.route, onClick = { nav.navigate(item.route) { launchSingleTop = true; popUpTo("home") { saveState = true }; restoreState = true } }, icon = { Icon(item.icon, item.label) }, label = { Text(item.label) })
                }
            }
        }
    ) { padding ->
        NavHost(navController = nav, startDestination = "home", modifier = Modifier.padding(padding)) {
            composable("home") { HomeScreen(vm, onBrowse = { nav.navigate("browse") }, onScan = { nav.navigate("scan") }) }
            composable("browse") { BrowseScreen(vm) }
            composable("scan") { ScannerScreen(vm) }
            composable("storage") { StorageScreen(vm) }
        }
    }
}

@Composable
private fun HomeScreen(vm: AppViewModel, onBrowse: () -> Unit, onScan: () -> Unit) {
    val cards by vm.catalog.collectAsStateWithLifecycle()
    val totals by vm.totals.collectAsStateWithLifecycle()
    val total = totals.sumOf { it.quantity }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text("Know where every card lives.", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Scan it. Find it. Choose it. Put it somewhere.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard("Physical cards", total.toString(), Modifier.weight(1f)); StatCard("Unique printings", totals.size.toString(), Modifier.weight(1f))
            }
        }
        item {
            Button(onClick = onScan, modifier = Modifier.fillMaxWidth().height(56.dp)) { Icon(Icons.Default.CameraAlt, null); Spacer(Modifier.size(8.dp)); Text("Start scanning") }
        }
        item { SectionTitle("Catalog", "${cards.size} cards available offline") }
        item { OutlinedButton(onClick = onBrowse, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Search, null); Spacer(Modifier.size(8.dp)); Text("Search and browse sets") } }
        item { Text("Recently indexed", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        item { LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) { items(cards.take(6), key = { it.id }) { CardTile(it, totals.firstOrNull { total -> total.cardId == it.id }?.quantity ?: 0, Modifier.size(132.dp, 206.dp)) } } }
    }
}

@Composable private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier) { Column(Modifier.padding(16.dp)) { Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold); Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
}

@Composable private fun SectionTitle(title: String, subtitle: String) { Column { Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant) } }

@Composable
private fun BrowseScreen(vm: AppViewModel) {
    val cards by vm.visibleCards.collectAsStateWithLifecycle()
    val totals by vm.totals.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    var ownership by remember { mutableStateOf("ALL") }
    var selectedSet by remember { mutableStateOf<String?>(null) }
    var rarity by remember { mutableStateOf<String?>(null) }
    val shown = cards.filter { card ->
        val owned = totals.any { total -> total.cardId == card.id && total.quantity > 0 }
        (ownership == "ALL" || ownership == "OWNED" && owned || ownership == "MISSING" && !owned) &&
            (selectedSet == null || card.setName == selectedSet) && (rarity == null || card.rarity == rarity)
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        OutlinedTextField(value = query, onValueChange = vm::setQuery, leadingIcon = { Icon(Icons.Default.Search, null) }, placeholder = { Text("Name, set, number, rarity") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
            item { FilterChip(selected = selectedSet == null, onClick = { selectedSet = null }, label = { Text("All sets") }) }
            item { FilterChip(selected = ownership == "OWNED", onClick = { ownership = if (ownership == "OWNED") "ALL" else "OWNED" }, label = { Text("Owned") }) }
            item { FilterChip(selected = ownership == "MISSING", onClick = { ownership = if (ownership == "MISSING") "ALL" else "MISSING" }, label = { Text("Missing") }) }
            items(cards.map { it.setName }.distinct()) { set -> FilterChip(selected = selectedSet == set, onClick = { selectedSet = if (selectedSet == set) null else set }, label = { Text(set, maxLines = 1) }) }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { FilterChip(selected = rarity == null, onClick = { rarity = null }, label = { Text("All rarities") }) }
            items(cards.map { it.rarity }.filter { it.isNotBlank() }.distinct()) { value -> FilterChip(selected = rarity == value, onClick = { rarity = if (rarity == value) null else value }, label = { Text(value) }) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(selectedSet ?: "All cards", fontWeight = FontWeight.Bold); Text("${shown.size} results", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        LazyVerticalGrid(columns = GridCells.Fixed(2), contentPadding = PaddingValues(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(shown, key = { it.id }) { card -> CardTile(card, totals.firstOrNull { it.cardId == card.id }?.quantity ?: 0, Modifier.fillMaxWidth()) }
        }
    }
}

@Composable
private fun CardTile(card: CardEntity, owned: Int, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Card(modifier = modifier.then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier), colors = CardDefaults.cardColors(containerColor = if (owned > 0) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)) {
        Box {
            AsyncImage(model = card.imageUrl, contentDescription = card.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().aspectRatio(2.5f / 3.5f).background(Color(0xFF232A35)))
            if (owned > 0) Text("×$owned", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).background(Color(0xDD285FC4), CircleShape).padding(horizontal = 8.dp, vertical = 4.dp))
        }
        Column(Modifier.padding(8.dp)) { Text(card.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold); Text("${card.setCode} ${card.collectorNumber} · ${card.rarity}", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall) }
    }
}

@Composable
private fun StorageScreen(vm: AppViewModel) {
    val cards by vm.catalog.collectAsStateWithLifecycle(); val totals by vm.totals.collectAsStateWithLifecycle()
    val allocations by vm.allocations.collectAsStateWithLifecycle(); val locations by vm.locations.collectAsStateWithLifecycle()
    val reviewItems by vm.reviewItems.collectAsStateWithLifecycle(); val sessions by vm.sessionSummaries.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf("") }; var type by remember { mutableStateOf("BINDER") }
    var moving by remember { mutableStateOf<AllocationEntity?>(null) }; var moveQuantity by remember { mutableStateOf(1) }; var moveDestination by remember { mutableStateOf(0L) }
    var reviewId by remember { mutableStateOf<Long?>(null) }; var reviewPath by remember { mutableStateOf("") }; var reviewCard by remember { mutableStateOf<CardEntity?>(null) }; var reviewDestination by remember { mutableStateOf(0L) }
    val context = LocalContext.current

    moving?.let { allocation ->
        AlertDialog(onDismissRequest = { moving = null }, title = { Text("Move physical copies") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(cards.firstOrNull { it.id == allocation.cardId }?.name ?: allocation.cardId, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) { OutlinedButton(onClick = { moveQuantity = (moveQuantity - 1).coerceAtLeast(1) }) { Text("−") }; Text("$moveQuantity of ${allocation.quantity}"); OutlinedButton(onClick = { moveQuantity = (moveQuantity + 1).coerceAtMost(allocation.quantity) }) { Text("+") } }
                locations.filter { it.id != allocation.locationId }.forEach { location -> FilterChip(selected = moveDestination == location.id, onClick = { moveDestination = location.id }, label = { Text(location.name) }) }
            }
        }, confirmButton = { Button(onClick = { vm.moveAllocation(allocation.id, moveQuantity, moveDestination); moving = null }, enabled = moveDestination != 0L) { Text("Move") } }, dismissButton = { OutlinedButton(onClick = { moving = null }) { Text("Cancel") } })
    }
    if (reviewId != null && reviewCard != null) {
        AlertDialog(onDismissRequest = { reviewId = null }, title = { Text("Resolve as ${reviewCard!!.name}") }, text = { Column { Text("Choose the physical destination"); locations.forEach { location -> FilterChip(selected = reviewDestination == location.id, onClick = { reviewDestination = location.id }, label = { Text(location.name) }) } } }, confirmButton = { Button(onClick = { vm.resolveReview(reviewId!!, reviewPath, reviewCard!!, reviewDestination); reviewId = null }, enabled = reviewDestination != 0L) { Text("Resolve") } }, dismissButton = { OutlinedButton(onClick = { reviewId = null }) { Text("Cancel") } })
    }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { SectionTitle("Physical storage", "Quantities are allocated, never duplicated"); IconButton(onClick = { ExportService.share(context, CollectionSnapshot(cards, totals, allocations, locations)) }) { Icon(Icons.Default.IosShare, "Export CSV and JSON") } } }
        if (reviewItems.isNotEmpty()) {
            item { SectionTitle("Review queue", "${reviewItems.size} uncertain ${if (reviewItems.size == 1) "scan" else "scans"}") }
            items(reviewItems, key = { "review-${it.id}" }) { review ->
                val candidateCards = review.candidateIds.split(',').mapNotNull { id -> cards.firstOrNull { it.id == id } }
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { AsyncImage(model = File(review.imagePath), contentDescription = "Captured card", modifier = Modifier.size(80.dp, 112.dp).clip(RoundedCornerShape(8.dp))); Column { Text("Needs exact printing", fontWeight = FontWeight.Bold); Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(review.createdAt)), style = MaterialTheme.typography.labelSmall); Text("Tap a candidate to resolve", color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                    if (candidateCards.isEmpty()) Text("No OCR candidates — rescan or wait for manual search support.")
                    else LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(candidateCards) { card -> FilledTonalButton(onClick = { reviewId = review.id; reviewPath = review.imagePath; reviewCard = card; reviewDestination = locations.firstOrNull()?.id ?: 0L }) { Text("${card.name} ${card.setCode} ${card.collectorNumber}") } } }
                } }
            }
        }
        items(locations, key = { it.id }) { location ->
            val rows = allocations.filter { it.locationId == location.id }; val qty = rows.sumOf { it.quantity }
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(12.dp).background(Color(location.color), CircleShape)); Spacer(Modifier.size(10.dp)); Column(Modifier.weight(1f)) { Text(location.name, fontWeight = FontWeight.Bold); Text(location.type, style = MaterialTheme.typography.labelSmall) }; Text("$qty cards") }; rows.take(5).forEach { row -> val card = cards.firstOrNull { it.id == row.cardId }; Row(Modifier.fillMaxWidth().clickable { moving = row; moveQuantity = 1; moveDestination = locations.firstOrNull { it.id != row.locationId }?.id ?: 0L }.padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) { Text("${card?.name ?: row.cardId} · ${row.variant}", style = MaterialTheme.typography.bodySmall); Text("×${row.quantity}  Move", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) } } } }
        }
        if (sessions.isNotEmpty()) {
            item { HorizontalDivider(); SectionTitle("Scan history", "Recent sessions") }
            items(sessions, key = { "session-${it.id}" }) { summary -> Card(Modifier.fillMaxWidth()) { Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Collections, null); Column(Modifier.weight(1f)) { Text(summary.name, fontWeight = FontWeight.Bold); Text("${summary.cardsScanned} cards · ${summary.uniqueCards} unique · ${summary.reviewItems} review", style = MaterialTheme.typography.bodySmall) }; Text(if (summary.endedAt == null) "ACTIVE" else DateFormat.getDateInstance(DateFormat.SHORT).format(Date(summary.startedAt)), style = MaterialTheme.typography.labelSmall) } } }
        }
        item { HorizontalDivider(); Text("Add a location", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        item { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, placeholder = { Text("e.g. German Binder") }, modifier = Modifier.fillMaxWidth()) }
        item { LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(listOf("BINDER", "FOLDER", "STORAGE", "COLLECTION")) { value -> FilterChip(selected = type == value, onClick = { type = value }, label = { Text(value.lowercase().replaceFirstChar { it.uppercase() }) }) } } }
        item { Button(onClick = { vm.createLocation(name, type); name = "" }, enabled = name.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Add, null); Text(" Create location") } }
    }
}

private enum class ScanPhase { READY, ANALYZING, CAPTURED, SELECTING, CONFIRMED }

@Composable
private fun ScannerScreen(vm: AppViewModel) {
    val context = LocalContext.current; val cards by vm.catalog.collectAsStateWithLifecycle(); val locations by vm.locations.collectAsStateWithLifecycle(); val session by vm.activeSession.collectAsStateWithLifecycle()
    var permission by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permission = it }
    var destination by remember(locations) { mutableStateOf(locations.firstOrNull()?.id ?: 0L) }; var sessionName by remember { mutableStateOf("Quick Scan") }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }; var phase by remember { mutableStateOf(ScanPhase.READY) }; var imagePath by remember { mutableStateOf("") }; var hint by remember { mutableStateOf("") }
    var selectedCard by remember { mutableStateOf<CardEntity?>(null) }; var selectedVariant by remember { mutableStateOf("Normal") }; var selectedLocation by remember { mutableStateOf(0L) }; var selectedQuantity by remember { mutableStateOf(1) }
    var recognition by remember { mutableStateOf<CardRecognitionResult?>(null) }
    val recognitionEngine = remember { CardRecognitionEngine(context.applicationContext) }
    val manuallyFiltered = cards.filter { hint.isBlank() || it.name.contains(hint, true) || it.collectorNumber.startsWith(hint) || it.setName.contains(hint, true) }
    val candidates = if (hint.isBlank() && recognition?.candidates?.isNotEmpty() == true) recognition!!.candidates.map { it.card } else manuallyFiltered.take(12)

    LaunchedEffect(phase, imagePath, cards.size) {
        if (phase == ScanPhase.ANALYZING && imagePath.isNotBlank() && cards.isNotEmpty()) {
            recognition = recognitionEngine.recognize(imagePath, cards)
            phase = ScanPhase.CAPTURED
        }
    }

    if (session == null) {
        Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SectionTitle("New scan session", "Choose where confirmed cards should go")
            OutlinedTextField(value = sessionName, onValueChange = { sessionName = it }, label = { Text("Session name") }, modifier = Modifier.fillMaxWidth())
            Text("Destination", fontWeight = FontWeight.Bold)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(locations) { location -> FilterChip(selected = destination == location.id, onClick = { destination = location.id }, label = { Text(location.name) }) } }
            Button(onClick = { if (destination != 0L) vm.startSession(sessionName, destination) }, enabled = destination != 0L, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Start session") }
        }
        return
    }
    val currentSession = session ?: return

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(currentSession.name, fontWeight = FontWeight.Bold); Text(locations.firstOrNull { it.id == currentSession.destinationId }?.name ?: "Unassigned", style = MaterialTheme.typography.labelMedium) }; OutlinedButton(onClick = { vm.endSession(currentSession) }) { Text("Finish") } }
        when (phase) {
            ScanPhase.READY -> if (!permission) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") } }
            } else {
                Box(Modifier.fillMaxSize()) {
                    CameraPreview(Modifier.fillMaxSize(), onCaptureReady = { imageCapture = it })
                    Box(Modifier.align(Alignment.Center).fillMaxWidth(0.72f).aspectRatio(2.5f / 3.5f).border(3.dp, MaterialTheme.colorScheme.secondary, RoundedCornerShape(18.dp)))
                    Text("Fit one card inside the frame", color = Color.White, modifier = Modifier.align(Alignment.TopCenter).padding(top = 18.dp).background(Color(0x99000000), RoundedCornerShape(20.dp)).padding(horizontal = 14.dp, vertical = 8.dp))
                    IconButton(onClick = { captureCard(context, imageCapture) { path -> imagePath = path; recognition = null; phase = ScanPhase.ANALYZING } }, enabled = imageCapture != null, modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp).size(76.dp).background(MaterialTheme.colorScheme.secondary, CircleShape)) { Icon(Icons.Default.CameraAlt, null, tint = Color.Black, modifier = Modifier.size(36.dp)) }
                }
            }
            ScanPhase.ANALYZING -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    CircularProgressIndicator(); Text("Reading card text…", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Text("Matching against the offline catalog")
                }
            }
            ScanPhase.CAPTURED -> Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                Text("Choose the exact printing", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                val signals = recognition?.signals
                Text(
                    if (signals?.collectorNumber != null || signals?.likelyName != null) "Read: ${signals.likelyName ?: "Unknown name"} · #${signals.collectorNumber ?: "?"}${signals.setCode?.let { " · $it" } ?: ""}" else "No reliable text found — search manually or save for review.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(value = hint, onValueChange = { hint = it }, leadingIcon = { Icon(Icons.Default.Search, null) }, label = { Text("Card name, set, or number") }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                LazyVerticalGrid(columns = GridCells.Fixed(2), modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { items(candidates, key = { it.id }) { card -> CardTile(card, 0, Modifier.fillMaxWidth(), onClick = { selectedCard = card; selectedVariant = card.variants.substringBefore(','); selectedLocation = currentSession.destinationId; selectedQuantity = 1; phase = ScanPhase.SELECTING }) } }
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton(onClick = { phase = ScanPhase.READY }, modifier = Modifier.weight(1f)) { Text("Retake") }; FilledTonalButton(onClick = { vm.queueReview(imagePath, candidates) { phase = ScanPhase.CONFIRMED } }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Warning, null); Text(" Review later") } }
            }
            ScanPhase.SELECTING -> {
                val card = selectedCard
                if (card == null) { LaunchedEffect(Unit) { phase = ScanPhase.CAPTURED } } else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    item { Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) { AsyncImage(model = card.imageUrl, contentDescription = card.name, modifier = Modifier.size(132.dp, 185.dp)); Column { Text(card.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text("${card.setName} · ${card.setCode} ${card.collectorNumber}"); Text(card.rarity, color = MaterialTheme.colorScheme.secondary) } } }
                    item { Text("Variant", fontWeight = FontWeight.Bold); LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(card.variants.split(',')) { variant -> FilterChip(selected = selectedVariant == variant, onClick = { selectedVariant = variant }, label = { Text(variant) }) } } }
                    item { Text("Destination", fontWeight = FontWeight.Bold); LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(locations) { location -> FilterChip(selected = selectedLocation == location.id, onClick = { selectedLocation = location.id }, label = { Text(location.name) }) } } }
                    item { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) { Text("Quantity", fontWeight = FontWeight.Bold); OutlinedButton(onClick = { selectedQuantity = (selectedQuantity - 1).coerceAtLeast(1) }) { Text("−") }; Text(selectedQuantity.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); OutlinedButton(onClick = { selectedQuantity++ }) { Text("+") } } }
                    item { Button(onClick = { vm.addCard(card, selectedVariant, selectedQuantity, selectedLocation) { phase = ScanPhase.CONFIRMED } }, enabled = selectedLocation != 0L, modifier = Modifier.fillMaxWidth().height(54.dp)) { Text("Confirm and put away") } }
                    item { OutlinedButton(onClick = { phase = ScanPhase.CAPTURED }, modifier = Modifier.fillMaxWidth()) { Text("Back to candidates") } }
                }
            }
            ScanPhase.CONFIRMED -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) { Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF48C78E), modifier = Modifier.size(72.dp)); Text("Saved", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold); Text("Remove the card before re-arming."); Button(onClick = { phase = ScanPhase.READY; hint = ""; imagePath = ""; recognition = null }) { Text("Card removed — scan next") } } }
        }
    }
}

@Composable
private fun CameraPreview(modifier: Modifier, onCaptureReady: (ImageCapture) -> Unit) {
    val context = LocalContext.current; val lifecycleOwner = LocalLifecycleOwner.current
    AndroidView(factory = { ctx -> PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }, modifier = modifier) { previewView ->
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            val provider = providerFuture.get(); val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }; val capture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
            runCatching { provider.unbindAll(); provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture); onCaptureReady(capture) }
        }, ContextCompat.getMainExecutor(context))
    }
}

private fun captureCard(context: Context, capture: ImageCapture?, onSaved: (String) -> Unit) {
    if (capture == null) return
    val file = File(context.cacheDir, "review_${System.currentTimeMillis()}.jpg")
    capture.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(), ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageSavedCallback {
        override fun onImageSaved(output: ImageCapture.OutputFileResults) = onSaved(file.absolutePath)
        override fun onError(exception: ImageCaptureException) = Unit
    })
}
