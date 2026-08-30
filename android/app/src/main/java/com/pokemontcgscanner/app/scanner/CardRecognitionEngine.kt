package com.pokemontcgscanner.app.scanner

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.pokemontcgscanner.app.data.CardEntity
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

data class RecognitionSignals(
    val rawText: String,
    val collectorNumber: String?,
    val collectorTotal: Int?,
    val likelyName: String?,
    val setCode: String?
)

data class RankedCandidate(val card: CardEntity, val score: Float, val reasons: List<String>)
enum class RecognitionConfidence { NONE, LOW, MEDIUM, HIGH }
data class CardRecognitionResult(
    val signals: RecognitionSignals,
    val candidates: List<RankedCandidate>,
    val confidence: RecognitionConfidence,
    val scoreMargin: Float
)

class CardRecognitionEngine(private val context: Context) {
    suspend fun recognize(imagePath: String, catalog: List<CardEntity>): CardRecognitionResult {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val text = suspendCancellableCoroutine<String> { continuation ->
            val image = runCatching { InputImage.fromFilePath(context, Uri.fromFile(File(imagePath))) }.getOrElse {
                continuation.resume(""); return@suspendCancellableCoroutine
            }
            recognizer.process(image)
                .addOnSuccessListener { if (continuation.isActive) continuation.resume(it.text) }
                .addOnFailureListener { if (continuation.isActive) continuation.resume("") }
                .addOnCompleteListener { recognizer.close() }
        }
        return CandidateRanker.rank(text, catalog)
    }
}

object CandidateRanker {
    private val printedNumber = Regex("(?i)\\b((?:TG|GG|RC|SV)?\\s*[0O]*\\d{1,3})\\s*[/|]\\s*([0O]*\\d{2,3})\\b")

    fun rank(rawText: String, catalog: List<CardEntity>): CardRecognitionResult {
        val normalizedText = normalize(rawText)
        val printed = printedNumber.findAll(rawText).toList().lastOrNull()
        val number = printed?.groupValues?.get(1)?.let(::normalizeCollectorNumber)
        val total = printed?.groupValues?.get(2)?.replace('O', '0', true)?.toIntOrNull()
        val setCode = catalog.map { it.setCode }.filter { it.length >= 2 }.distinct()
            .sortedByDescending { it.length }.firstOrNull { code ->
            Regex("(?i)(^|[^A-Z])${Regex.escape(code)}([^A-Z]|$)").containsMatchIn(rawText)
        }
        val names = catalog.map { it.name }.distinct()
        val exactName = names.sortedByDescending { it.length }.firstOrNull { name ->
            normalizedText.contains(normalize(name))
        }
        val nameSimilarities = if (exactName == null) {
            names.associateWith { name -> bestNameSimilarity(rawText, name) }
        } else {
            emptyMap()
        }
        val likelyName = exactName ?: nameSimilarities.maxByOrNull { it.value }
            ?.takeIf { it.value >= 0.62f }?.key

        val signals = RecognitionSignals(rawText, number, total, likelyName, setCode)
        val ranked = catalog.mapNotNull { card ->
            var score = 0f
            val reasons = mutableListOf<String>()
            val numberMatches = number != null && normalizeCollectorNumber(card.collectorNumber) == number
            val totalMatches = total != null && total > 0 && (card.setPrintedTotal == total || card.setTotal == total)
            if (numberMatches) {
                score += 0.38f; reasons += "collector number"
            }
            if (totalMatches) {
                score += 0.30f; reasons += "set total"
            }
            if (numberMatches && totalMatches) {
                score += 0.08f
            }
            if (setCode != null && card.setCode.equals(setCode, true)) {
                score += 0.24f; reasons += "set code"
            }
            val nameScore = when {
                likelyName != null && card.name.equals(likelyName, true) -> 0.42f
                normalizedText.contains(normalize(card.name)) -> 0.38f
                else -> (nameSimilarities[card.name] ?: bestNameSimilarity(rawText, card.name)) * 0.26f
            }
            if (nameScore >= 0.13f) { score += nameScore; reasons += "name text" }
            if (card.regulationMark.isNotBlank() && Regex("(?i)\\b${Regex.escape(card.regulationMark)}\\b").containsMatchIn(rawText)) {
                score += 0.04f; reasons += "regulation mark"
            }
            if (score >= 0.18f) RankedCandidate(card, score.coerceAtMost(1f), reasons) else null
        }.sortedWith(
            compareByDescending<RankedCandidate> { it.score }
                .thenByDescending { it.reasons.size }
                .thenBy { it.card.setName }
                .thenBy { it.card.collectorNumber }
        ).take(12)
        val margin = ((ranked.firstOrNull()?.score ?: 0f) - (ranked.getOrNull(1)?.score ?: 0f)).coerceAtLeast(0f)
        val top = ranked.firstOrNull()?.score ?: 0f
        val confidence = when {
            ranked.isEmpty() -> RecognitionConfidence.NONE
            top >= 0.78f && margin >= 0.12f -> RecognitionConfidence.HIGH
            top >= 0.58f && margin >= 0.06f -> RecognitionConfidence.MEDIUM
            else -> RecognitionConfidence.LOW
        }
        return CardRecognitionResult(signals, ranked, confidence, margin)
    }

    private fun bestNameSimilarity(rawText: String, name: String): Float = rawText.lineSequence()
        .map { similarity(normalize(it), normalize(name)) }.maxOrNull() ?: 0f

    internal fun similarity(left: String, right: String): Float {
        if (left.isBlank() || right.isBlank()) return 0f
        if (left == right) return 1f
        val previous = IntArray(right.length + 1) { it }
        for (i in left.indices) {
            var diagonal = previous[0]
            previous[0] = i + 1
            for (j in right.indices) {
                val old = previous[j + 1]
                previous[j + 1] = minOf(previous[j + 1] + 1, previous[j] + 1, diagonal + if (left[i] == right[j]) 0 else 1)
                diagonal = old
            }
        }
        return 1f - previous[right.length].toFloat() / maxOf(left.length, right.length)
    }

    internal fun normalizeCollectorNumber(value: String): String {
        val compact = value.uppercase().replace(Regex("[^A-Z0-9]"), "").replace('O', '0')
        val prefix = compact.takeWhile { it.isLetter() }
        val digits = compact.drop(prefix.length).trimStart('0').ifEmpty { "0" }
        return prefix + digits
    }

    private fun normalize(value: String) = value.lowercase().replace("é", "e").replace(Regex("[^a-z0-9]+"), " ").trim()
}
