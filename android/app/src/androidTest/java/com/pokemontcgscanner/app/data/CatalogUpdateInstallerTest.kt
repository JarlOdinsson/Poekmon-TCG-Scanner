package com.pokemontcgscanner.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CatalogUpdateInstallerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before fun resetInstalledCatalogue() { context.deleteDatabase("pokemon_catalog.db") }
    @After fun cleanUp() { context.deleteDatabase("pokemon_catalog.db") }

    @Test fun validNewerBundleInstallsAndRollbackIsRejected() = runBlocking {
        val cards = CatalogDatabase.create(context).cards()
        val count = cards.count()
        val bundle = createVersionThreeBundle(count)
        val result = cards.installUpdate(ByteArrayInputStream(bundle))
        assertEquals(3, result.catalogVersion)
        assertEquals(count, result.cardCount)
        assertEquals(3, cards.catalogVersion())
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { cards.installUpdate(ByteArrayInputStream(bundle)) }
        }
    }

    private fun createVersionThreeBundle(cardCount: Int): ByteArray {
        val database = File(context.cacheDir, "catalog-en-v3.sqlite")
        context.assets.open("databases/catalog-en.sqlite").use { input ->
            database.outputStream().use { output -> input.copyTo(output) }
        }
        SQLiteDatabase.openDatabase(database.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("UPDATE catalog_metadata SET value='3' WHERE key='catalogVersion'")
        }
        val variantCount = SQLiteDatabase.openDatabase(database.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT COUNT(*) FROM card_variants", null).use { cursor -> cursor.moveToFirst(); cursor.getInt(0) }
        }
        val hash = MessageDigest.getInstance("SHA-256").digest(database.readBytes()).joinToString("") { "%02x".format(it) }
        val manifest = JSONObject().apply {
            put("catalogVersion", 3); put("schemaVersion", 2); put("language", "en")
            put("databaseFile", database.name); put("databaseSha256", hash)
            put("cardCount", cardCount); put("variantCount", variantCount)
        }.toString()
        return ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { zip ->
                zip.putNextEntry(ZipEntry("catalog-manifest.json")); zip.write(manifest.toByteArray()); zip.closeEntry()
                zip.putNextEntry(ZipEntry(database.name)); database.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }
            database.delete()
        }.toByteArray()
    }
}
