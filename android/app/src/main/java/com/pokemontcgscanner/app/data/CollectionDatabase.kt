package com.pokemontcgscanner.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "locations")
data class LocationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: String,
    val color: Long = 0xFF5B8CFF
)

@Entity(
    tableName = "allocations",
    indices = [Index(value = ["cardId", "variant", "locationId", "status"], unique = true)]
)
data class AllocationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val cardId: String,
    val variant: String,
    val locationId: Long,
    val status: String,
    val quantity: Int,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "scan_sessions")
data class ScanSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val destinationId: Long,
    val startedAt: Long = System.currentTimeMillis(),
    val endedAt: Long? = null
)

@Entity(tableName = "scan_events")
data class ScanEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val cardId: String,
    val variant: String,
    val quantity: Int,
    val confidence: Float,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "review_items")
data class ReviewItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long?,
    val imagePath: String,
    val candidateIds: String,
    val note: String,
    val resolved: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

data class InventoryTotal(val cardId: String, val quantity: Int)
data class SessionSummary(
    val id: Long,
    val name: String,
    val destinationId: Long,
    val startedAt: Long,
    val endedAt: Long?,
    val cardsScanned: Int,
    val uniqueCards: Int,
    val reviewItems: Int
)

@Dao
interface CollectionDao {
    @Query("SELECT * FROM locations ORDER BY name") fun observeLocations(): Flow<List<LocationEntity>>
    @Query("SELECT * FROM allocations ORDER BY updatedAt DESC") fun observeAllocations(): Flow<List<AllocationEntity>>
    @Query("SELECT cardId, SUM(quantity) AS quantity FROM allocations GROUP BY cardId") fun observeTotals(): Flow<List<InventoryTotal>>
    @Query("SELECT * FROM scan_sessions WHERE endedAt IS NULL ORDER BY startedAt DESC LIMIT 1") fun observeActiveSession(): Flow<ScanSessionEntity?>
    @Query("SELECT * FROM review_items WHERE resolved = 0 ORDER BY createdAt DESC") fun observeReviewItems(): Flow<List<ReviewItemEntity>>
    @Query("""SELECT s.id, s.name, s.destinationId, s.startedAt, s.endedAt,
        COALESCE((SELECT SUM(e.quantity) FROM scan_events e WHERE e.sessionId = s.id), 0) AS cardsScanned,
        COALESCE((SELECT COUNT(DISTINCT e.cardId) FROM scan_events e WHERE e.sessionId = s.id), 0) AS uniqueCards,
        COALESCE((SELECT COUNT(*) FROM review_items r WHERE r.sessionId = s.id), 0) AS reviewItems
        FROM scan_sessions s ORDER BY s.startedAt DESC LIMIT 20""")
    fun observeSessionSummaries(): Flow<List<SessionSummary>>
    @Query("SELECT COUNT(*) FROM locations") suspend fun locationCount(): Int
    @Query("SELECT * FROM locations ORDER BY id LIMIT 1") suspend fun firstLocation(): LocationEntity?
    @Query("SELECT * FROM allocations WHERE cardId = :cardId AND variant = :variant AND locationId = :locationId AND status = :status LIMIT 1")
    suspend fun allocation(cardId: String, variant: String, locationId: Long, status: String): AllocationEntity?
    @Query("SELECT * FROM allocations WHERE id = :id") suspend fun allocationById(id: Long): AllocationEntity?

    @Insert suspend fun insertLocation(location: LocationEntity): Long
    @Insert suspend fun insertSession(session: ScanSessionEntity): Long
    @Insert suspend fun insertEvent(event: ScanEventEntity): Long
    @Insert suspend fun insertReviewItem(item: ReviewItemEntity): Long
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertAllocation(allocation: AllocationEntity): Long
    @Query("UPDATE scan_sessions SET endedAt = :endedAt WHERE id = :id") suspend fun endSession(id: Long, endedAt: Long)
    @Query("UPDATE review_items SET resolved = 1 WHERE id = :id") suspend fun resolveReviewItem(id: Long)
    @Query("DELETE FROM allocations WHERE id = :id") suspend fun deleteAllocation(id: Long)
}

@Database(
    entities = [LocationEntity::class, AllocationEntity::class, ScanSessionEntity::class, ScanEventEntity::class, ReviewItemEntity::class],
    version = 1,
    exportSchema = false
)
abstract class CollectionDatabase : RoomDatabase() {
    abstract fun collection(): CollectionDao

    companion object {
        fun create(context: Context) = Room.databaseBuilder(
            context,
            CollectionDatabase::class.java,
            "user_collection.db"
        ).fallbackToDestructiveMigration().build()
    }
}
