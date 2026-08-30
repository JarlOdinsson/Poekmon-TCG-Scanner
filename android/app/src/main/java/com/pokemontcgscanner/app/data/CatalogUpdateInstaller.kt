package com.pokemontcgscanner.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.core.util.AtomicFile
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream
import org.json.JSONObject

data class CatalogUpdateResult(val catalogVersion: Int, val cardCount: Int, val variantCount: Int)

class CatalogUpdateInstaller(private val context: Context) {
    fun install(input: InputStream, installedVersion: Int): CatalogUpdateResult {
        val staging = File(context.cacheDir, "catalog-update-${UUID.randomUUID()}")
        check(staging.mkdirs()) { "Could not create catalogue staging directory" }
        try {
            var manifestText: String? = null
            var database: File? = null
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(!entry.isDirectory && !entry.name.contains('/') && !entry.name.contains('\\')) {
                        "Catalogue bundle contains an unexpected path"
                    }
                    when {
                        entry.name == "catalog-manifest.json" -> {
                            require(manifestText == null) { "Duplicate catalogue manifest" }
                            manifestText = zip.readLimited(MAX_MANIFEST_BYTES).toString(Charsets.UTF_8)
                        }
                        Regex("catalog-en-v\\d+\\.sqlite").matches(entry.name) -> {
                            require(database == null) { "Duplicate catalogue database" }
                            database = File(staging, entry.name).also { target ->
                                target.outputStream().use { output -> zip.copyLimited(output, MAX_DATABASE_BYTES) }
                            }
                        }
                        else -> throw IllegalArgumentException("Unexpected catalogue bundle entry: ${entry.name}")
                    }
                    zip.closeEntry()
                }
            }
            val metadata = parseManifest(manifestText ?: throw IllegalArgumentException("Catalogue manifest is missing"))
            val stagedDatabase = database ?: throw IllegalArgumentException("Catalogue database is missing")
            require(stagedDatabase.name == metadata.databaseFile) { "Manifest database filename does not match the bundle" }
            CatalogUpdatePolicy.validate(metadata, installedVersion)
            require(sha256(stagedDatabase) == metadata.databaseSha256) { "Catalogue database checksum mismatch" }
            validateDatabase(stagedDatabase, metadata)
            installAtomically(stagedDatabase)
            return CatalogUpdateResult(metadata.catalogVersion, metadata.cardCount, metadata.variantCount)
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun parseManifest(raw: String): CatalogUpdateMetadata {
        val json = runCatching { JSONObject(raw) }.getOrElse { throw IllegalArgumentException("Catalogue manifest is invalid") }
        return CatalogUpdateMetadata(
            catalogVersion = json.optInt("catalogVersion", -1), schemaVersion = json.optInt("schemaVersion", -1),
            language = json.optString("language"), databaseFile = json.optString("databaseFile"),
            databaseSha256 = json.optString("databaseSha256"), cardCount = json.optInt("cardCount", -1),
            variantCount = json.optInt("variantCount", -1)
        )
    }

    private fun validateDatabase(file: File, metadata: CatalogUpdateMetadata) {
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { database ->
            require(database.rawQuery("PRAGMA integrity_check", null).use { it.moveToFirst() && it.getString(0) == "ok" }) {
                "Catalogue database integrity check failed"
            }
            val storedMetadata = database.rawQuery("SELECT key,value FROM catalog_metadata", null).use { cursor ->
                buildMap { while (cursor.moveToNext()) put(cursor.getString(0), cursor.getString(1)) }
            }
            require(storedMetadata["catalogVersion"]?.toIntOrNull() == metadata.catalogVersion) { "Database catalogue version mismatch" }
            require(storedMetadata["schemaVersion"]?.toIntOrNull() == metadata.schemaVersion) { "Database schema version mismatch" }
            require(storedMetadata["language"] == metadata.language) { "Database language mismatch" }
            require(database.scalar("SELECT COUNT(*) FROM cards") == metadata.cardCount) { "Database card count mismatch" }
            require(database.scalar("SELECT COUNT(*) FROM card_variants") == metadata.variantCount) { "Database variant count mismatch" }
            require(database.scalar("SELECT COUNT(*) FROM cards c WHERE NOT EXISTS (SELECT 1 FROM card_variants v WHERE v.card_id=c.internal_id)") == 0) {
                "Catalogue contains cards without variant identities"
            }
            val columns = database.rawQuery("PRAGMA table_info(card_variants)", null).use { cursor ->
                buildSet { while (cursor.moveToNext()) add(cursor.getString(1)) }
            }
            require(setOf("evidence_status", "provenance_source", "provenance_ref").all { it in columns }) {
                "Catalogue provenance columns are missing"
            }
        }
    }

    private fun installAtomically(database: File) {
        val target = context.getDatabasePath(DATABASE_NAME)
        target.parentFile?.mkdirs()
        val atomic = AtomicFile(target)
        val output = atomic.startWrite()
        try {
            database.inputStream().use { it.copyTo(output, 1024 * 1024) }
            atomic.finishWrite(output)
        } catch (error: Throwable) {
            atomic.failWrite(output)
            throw error
        }
    }

    private fun SQLiteDatabase.scalar(sql: String): Int = rawQuery(sql, null).use { cursor ->
        if (cursor.moveToFirst()) cursor.getInt(0) else 0
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun InputStream.readLimited(limit: Long): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        copyLimited(output, limit)
        return output.toByteArray()
    }

    private fun InputStream.copyLimited(output: java.io.OutputStream, limit: Long) {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            require(total <= limit) { "Catalogue bundle entry is too large" }
            output.write(buffer, 0, count)
        }
    }

    companion object {
        private const val DATABASE_NAME = "pokemon_catalog.db"
        private const val MAX_MANIFEST_BYTES = 1024L * 1024L
        private const val MAX_DATABASE_BYTES = 100L * 1024L * 1024L
    }
}
