package com.pokemontcgscanner.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CollectionMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val databaseName = "user_collection.db"

    @Before fun createRealisticVersionOneDatabase() {
        context.deleteDatabase(databaseName)
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(databaseName), null).use { db ->
            db.execSQL("CREATE TABLE locations (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, type TEXT NOT NULL, color INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE allocations (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, cardId TEXT NOT NULL, variant TEXT NOT NULL, locationId INTEGER NOT NULL, status TEXT NOT NULL, quantity INTEGER NOT NULL, updatedAt INTEGER NOT NULL)")
            db.execSQL("CREATE UNIQUE INDEX index_allocations_cardId_variant_locationId_status ON allocations(cardId,variant,locationId,status)")
            db.execSQL("CREATE TABLE scan_sessions (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, destinationId INTEGER NOT NULL, startedAt INTEGER NOT NULL, endedAt INTEGER)")
            db.execSQL("CREATE TABLE scan_events (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, sessionId INTEGER NOT NULL, cardId TEXT NOT NULL, variant TEXT NOT NULL, quantity INTEGER NOT NULL, confidence REAL NOT NULL, createdAt INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE review_items (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, sessionId INTEGER, imagePath TEXT NOT NULL, candidateIds TEXT NOT NULL, note TEXT NOT NULL, resolved INTEGER NOT NULL, createdAt INTEGER NOT NULL)")
            db.execSQL("INSERT INTO locations VALUES(5,'Legacy Binder','BINDER',123)")
            db.execSQL("INSERT INTO scan_sessions VALUES(7,'Legacy Session',5,1000,NULL)")
            db.execSQL("INSERT INTO scan_events VALUES(8,7,'sv3pt5-7','Normal',3,1.0,1001)")
            db.execSQL("INSERT INTO review_items VALUES(9,7,'legacy.jpg','sv3pt5-7','Keep me',0,1002)")
            db.execSQL("INSERT INTO allocations VALUES(10,'sv3pt5-7','Normal',5,'AVAILABLE',3,1003)")
            db.execSQL("INSERT INTO allocations VALUES(11,'tcgdex-en:base1-77','Normal · Shadowless · 1St Edition',5,'AVAILABLE',2,1004)")
            db.execSQL("INSERT INTO allocations VALUES(12,'base1-4','Unknown',5,'AVAILABLE',4,1005)")
            db.version = 1
        }
    }

    @After fun cleanUp() { context.deleteDatabase(databaseName) }

    @Test fun migrationAndReconciliationPreserveEverythingAndAreRetrySafe() = runBlocking {
        val repository = AppRepository(context)
        repository.initialize()
        repository.initialize()
        val nextLocationId = repository.createLocation("Post-migration Box", "STORAGE")
        assertEquals(6L, nextLocationId)

        SQLiteDatabase.openDatabase(context.getDatabasePath(databaseName).absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            assertEquals(2, db.version)
            assertEquals(1, scalar(db, "SELECT COUNT(*) FROM locations WHERE id=5 AND name='Legacy Binder'"))
            assertEquals(1, scalar(db, "SELECT COUNT(*) FROM locations WHERE id=6 AND name='Post-migration Box'"))
            assertEquals(1, scalar(db, "SELECT COUNT(*) FROM scan_sessions WHERE id=7"))
            assertEquals(1, scalar(db, "SELECT COUNT(*) FROM scan_events WHERE id=8 AND variantDisplayName='Normal' AND variantId IS NOT NULL"))
            assertEquals(1, scalar(db, "SELECT COUNT(*) FROM review_items WHERE id=9 AND resolved=0"))
            assertEquals(3, scalar(db, "SELECT COUNT(*) FROM allocations"))
            assertEquals(9, scalar(db, "SELECT SUM(quantity) FROM allocations"))
            assertEquals("ok", db.rawQuery("PRAGMA integrity_check", null).use { cursor -> cursor.moveToFirst(); cursor.getString(0) })

            db.rawQuery("SELECT variantId,variantResolution,quantity FROM allocations WHERE id=10", null).use { cursor ->
                cursor.moveToFirst()
                assertNotNull(cursor.getString(0))
                assertEquals(VariantResolutionState.RESOLVED, cursor.getString(1))
                assertEquals(3, cursor.getInt(2))
            }
            db.rawQuery("SELECT variantId,variantResolution,quantity FROM allocations WHERE id=11", null).use { cursor ->
                cursor.moveToFirst()
                assertNull(cursor.getString(0))
                assertEquals(VariantResolutionState.AMBIGUOUS, cursor.getString(1))
                assertEquals(2, cursor.getInt(2))
            }
            db.rawQuery("SELECT variantId,variantResolution,variantDisplayName,quantity FROM allocations WHERE id=12", null).use { cursor ->
                cursor.moveToFirst()
                assertNull(cursor.getString(0))
                assertEquals(VariantResolutionState.UNMATCHED, cursor.getString(1))
                assertEquals("Unknown", cursor.getString(2))
                assertEquals(4, cursor.getInt(3))
            }
        }
    }

    @Test fun newSameDisplayVariantsRemainSeparateStableAllocations() = runBlocking {
        val repository = AppRepository(context)
        repository.initialize()
        val variants = repository.variantsFor("tcgdex-en:base1-77")
        val duplicateDisplay = variants.groupBy { it.displayName }.values.first { it.size > 1 }.take(2)
        duplicateDisplay.forEach { variant ->
            val confirmation = AllocationConfirmation.create(
                cardId = variant.cardId,
                selectedVariantId = variant.id,
                variants = variants,
                quantity = 1,
                locationId = 5
            )!!
            repository.confirmAllocation(confirmation, "AVAILABLE", null, null)
        }

        SQLiteDatabase.openDatabase(context.getDatabasePath(databaseName).absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            assertEquals(2, scalar(db, "SELECT COUNT(*) FROM allocations WHERE cardId='tcgdex-en:base1-77' AND variantId IS NOT NULL"))
            assertEquals(2, scalar(db, "SELECT COUNT(DISTINCT variantId) FROM allocations WHERE cardId='tcgdex-en:base1-77' AND variantId IS NOT NULL"))
        }
    }

    private fun scalar(db: SQLiteDatabase, sql: String): Int = db.rawQuery(sql, null).use { cursor ->
        cursor.moveToFirst()
        cursor.getInt(0)
    }
}
