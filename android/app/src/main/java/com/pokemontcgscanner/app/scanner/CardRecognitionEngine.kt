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
    val likelyName: String?,
    val setCode: String?
)

data class RankedCandidate(val card: CardEntity, val score: Float, val reasons: List<String>)
data class CardRecognitionResult(val signals: RecognitionSignals, val candidates: List<RankedCandidate>)

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
    private val printedNumber = Regex("(?i)\\b(\\d{1,3})\\s*[/|]\\s*(\\d{1,3})\\b")

    fun rank(rawText: String, catalog: List<CardEntity>): CardRecognitionResult {
        val normalizedText = normalize(rawText)
        val number = printedNumber.find(rawText)?.groupValues?.get(1)?.trimStart('0')?.ifEmpty { "0" }
        val setCode = catalog.map { it.setCode }.distinct().firstOrNull { code ->
            Regex("(?i)(^|[^A-Z])${Regex.escape(code)}([^A-Z]|$)").containsMatchIn(rawText)
        }
        val likelyName = catalog.map { it.name }.distinct().sortedByDescending { it.length }.firstOrNull { name ->
            normalizedText.contains(normalize(name))
        } ?: bestLineName(rawText, catalog)

        val signals = RecognitionSignals(rawText, number, likelyName, setCode)
        val ranked = catalog.mapNotNull { card ->
            var score = 0f
            val reasons = mutableListOf<String>()
            if (number != null && card.collectorNumber.trimStart('0').ifEmpty { "0" } == number) {
                score += 0.56f; reasons += "collector number"
            }
            if (setCode != null && card.setCode.equals(setCode, true)) {
                score += 0.18f; reasons += "set code"
            }
            val nameScore = when {
                likelyName != null && card.name.equals(likelyName, true) -> 0.36f
                normalizedText.contains(normalize(card.name)) -> 0.32f
                else -> bestNameSimilarity(rawText, card.name) * 0.22f
            }
            if (nameScore >= 0.1f) { score += nameScore; reasons += "name text" }
            if (card.regulationMark.isNotBlank() && Regex("(?i)\\b${Regex.escape(card.regulationMark)}\\b").containsMatchIn(rawText)) {
                score += 0.04f; reasons += "regulation mark"
            }
            if (score >= 0.12f) RankedCandidate(card, score.coerceAtMost(1f), reasons) else null
        }.sortedWith(compareByDescending<RankedCandidate> { it.score }.thenBy { it.card.setName }).take(12)
        return CardRecognitionResult(signals, ranked)
    }

    private fun bestLineName(rawText: String, catalog: List<CardEntity>): String? = catalog.map { card ->
        card.name to bestNameSimilarity(rawText, card.name)
    }.maxByOrNull { it.second }?.takeIf { it.second >= 0.62f }?.first

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

    private fun normalize(value: String) = value.lowercase().replace("é", "e").replace(Regex("[^a-z0-9]+"), " ").trim()
}
