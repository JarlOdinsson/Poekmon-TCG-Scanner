package com.pokemontcgscanner.app.data

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.core.util.AtomicFile
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class CardEntity(
    val id: String,
    val name: String,
    val setId: String,
    val setName: String,
    val setCode: String,
    val collectorNumber: String,
    val supertype: String,
    val subtypes: String,
    val types: String,
    val rarity: String,
    val regulationMark: String,
    val artist: String,
    val language: String = "en",
    val variants: String = "Unknown",
    val standardLegal: Boolean = false,
    val expandedLegal: Boolean = false,
    val imageUrl: String = ""
)

class CardDao internal constructor(private val context: Context) {
    fun observeAll(): Flow<List<CardEntity>> = flow { emit(loadAll()) }.flowOn(Dispatchers.IO)

    suspend fun search(query: String): List<CardEntity> = withContext(Dispatchers.IO) {
        ensureInstalled()
        if (query.isBlank()) return@withContext loadAll().take(100)
        val tokens = Regex("[\\p{L}\\p{N}]+").findAll(query).map { it.value }.toList()
        if (tokens.isEmpty()) return@withContext emptyList()
        val expression = tokens.joinToString(" AND ") { "\"$it\"*" }
        openReadOnly().use { database ->
            database.rawQuery(
                """SELECT a.* FROM card_search s JOIN card_app a ON a.id=s.internal_id
                   WHERE card_search MATCH ? ORDER BY bm25(card_search), a.name LIMIT 100""",
                arrayOf(expression)
            ).use(::readCards)
        }
    }

    suspend fun count(): Int = withContext(Dispatchers.IO) {
        ensureInstalled()
        openReadOnly().use { database ->
            database.rawQuery("SELECT COUNT(*) FROM cards", null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0) else 0
            }
        }
    }

    private fun loadAll(): List<CardEntity> {
        ensureInstalled()
        return openReadOnly().use { database ->
            database.rawQuery(
                "SELECT * FROM card_app ORDER BY setName, CAST(collectorNumber AS INTEGER), collectorNumber",
                null
            ).use(::readCards)
        }
    }

    private fun readCards(cursor: Cursor): List<CardEntity> = buildList(cursor.count) {
        val columns = cursor.columnNames.withIndex().associate { it.value to it.index }
        while (cursor.moveToNext()) {
            fun text(name: String) = cursor.getString(columns.getValue(name)) ?: ""
            add(
                CardEntity(
                    id = text("id"), name = text("name"), setId = text("setId"),
                    setName = text("setName"), setCode = text("setCode"), collectorNumber = text("collectorNumber"),
                    supertype = text("supertype"), subtypes = text("subtypes"), types = text("types"),
                    rarity = text("rarity"), regulationMark = text("regulationMark"), artist = text("artist"),
                    language = text("language"), variants = text("variants").ifBlank { "Unknown" },
                    standardLegal = cursor.getInt(columns.getValue("standardLegal")) == 1,
                    expandedLegal = cursor.getInt(columns.getValue("expandedLegal")) == 1,
                    imageUrl = text("imageUrl")
                )
            )
        }
    }

    private fun openReadOnly(): SQLiteDatabase = SQLiteDatabase.openDatabase(
        context.getDatabasePath(DATABASE_NAME).absolutePath,
        null,
        SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
    )

    @Synchronized
    private fun ensureInstalled() {
        val target = context.getDatabasePath(DATABASE_NAME)
        val expectedVersion = context.assets.open(MANIFEST_ASSET).bufferedReader().use {
            JSONObject(it.readText()).getInt("catalogVersion")
        }
        if (target.exists() && installedVersion(target) == expectedVersion) return
        target.parentFile?.mkdirs()
        val atomic = AtomicFile(target)
        val output = atomic.startWrite()
        try {
            context.assets.open(DATABASE_ASSET).use { input -> input.copyTo(output, 1024 * 1024) }
            atomic.finishWrite(output)
        } catch (error: Throwable) {
            atomic.failWrite(output)
            throw error
        }
    }

    private fun installedVersion(file: File): Int = runCatching {
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { database ->
            database.rawQuery("SELECT value FROM catalog_metadata WHERE key='catalogVersion'", null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0).toInt() else -1
            }
        }
    }.getOrDefault(-1)

    companion object {
        private const val DATABASE_NAME = "pokemon_catalog.db"
        private const val DATABASE_ASSET = "databases/catalog-en.sqlite"
        private const val MANIFEST_ASSET = "databases/catalog-manifest.json"
    }
}

class CatalogDatabase private constructor(context: Context) {
    private val dao = CardDao(context.applicationContext)
    fun cards(): CardDao = dao

    companion object { fun create(context: Context) = CatalogDatabase(context) }
}
