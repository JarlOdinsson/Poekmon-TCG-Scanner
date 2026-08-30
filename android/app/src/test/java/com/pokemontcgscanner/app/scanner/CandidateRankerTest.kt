package com.pokemontcgscanner.app.scanner

import com.pokemontcgscanner.app.data.CardEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CandidateRankerTest {
    private val squirtle7 = card("sv3pt5-7", "Squirtle", "007", 165)
    private val squirtle170 = card("sv3pt5-170", "Squirtle", "170", 165)
    private val charmander4 = card("sv3pt5-4", "Charmander", "004", 165)

    @Test fun `collector number and name rank exact printing first`() {
        val result = CandidateRanker.rank("BASIC Squirtle HP 70\n007/165 MEW G", listOf(squirtle170, charmander4, squirtle7))
        assertEquals("sv3pt5-7", result.candidates.first().card.id)
        assertEquals("7", result.signals.collectorNumber)
        assertEquals("Squirtle", result.signals.likelyName)
        assertEquals(165, result.signals.collectorTotal)
    }

    @Test fun `fuzzy OCR name still produces useful candidate`() {
        val result = CandidateRanker.rank("Squirtie\n170/165", listOf(squirtle7, squirtle170, charmander4))
        assertEquals("sv3pt5-170", result.candidates.first().card.id)
        assertTrue(result.candidates.first().score > .6f)
    }

    @Test fun `unrelated text does not pretend to identify a card`() {
        assertTrue(CandidateRanker.rank("completely unrelated", listOf(squirtle7)).candidates.isEmpty())
    }

    @Test fun `printed set total disambiguates the same collector number across sets`() {
        val otherSet = card("other-7", "Squirtle", "007", 198)
        val result = CandidateRanker.rank("Squirtle\n007/165", listOf(otherSet, squirtle7))
        assertEquals(squirtle7.id, result.candidates.first().card.id)
        assertTrue(result.candidates.first().reasons.contains("set total"))
        assertEquals(RecognitionConfidence.HIGH, result.confidence)
    }

    @Test fun `alphanumeric subset numbers survive OCR normalization`() {
        val gallery = card("gallery-1", "Pikachu", "TG01", 30)
        val result = CandidateRanker.rank("Pikachu\nTG01/030", listOf(gallery))
        assertEquals("TG1", result.signals.collectorNumber)
        assertEquals(gallery.id, result.candidates.first().card.id)
    }

    @Test fun `number alone remains low confidence when several sets collide`() {
        val otherSet = card("other-7", "Bulbasaur", "007", 198)
        val result = CandidateRanker.rank("007", listOf(otherSet, squirtle7))
        assertEquals(RecognitionConfidence.NONE, result.confidence)
    }

    private fun card(id: String, name: String, number: String, total: Int) = CardEntity(
        id, name, "sv3pt5", "Scarlet & Violet—151", "MEW", number, "Pokémon", "Basic", "Water", "Common", "G", "Artist", standardLegal = true, expandedLegal = true, imageUrl = "", setPrintedTotal = total, setTotal = total
    )
}
