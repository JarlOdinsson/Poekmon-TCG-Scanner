package com.pokemontcgscanner.app.data

import org.json.JSONArray
import org.json.JSONObject

data class CollectionBackupSnapshot(
    val catalogVersion: Int,
    val locations: List<LocationEntity>,
    val allocations: List<AllocationEntity>,
    val sessions: List<ScanSessionEntity>,
    val events: List<ScanEventEntity>,
    val reviewItems: List<ReviewItemEntity>
)

object CollectionBackupCodec {
    private const val FORMAT = "carddex-collection-backup"
    private const val VERSION = 1

    fun encode(snapshot: CollectionBackupSnapshot): String = JSONObject().apply {
        put("format", FORMAT)
        put("version", VERSION)
        put("catalogVersion", snapshot.catalogVersion)
        put("locations", JSONArray().apply { snapshot.locations.forEach { item -> put(item.toJson()) } })
        put("allocations", JSONArray().apply { snapshot.allocations.forEach { item -> put(item.toJson()) } })
        put("sessions", JSONArray().apply { snapshot.sessions.forEach { item -> put(item.toJson()) } })
        put("events", JSONArray().apply { snapshot.events.forEach { item -> put(item.toJson()) } })
        put("reviewItems", JSONArray().apply { snapshot.reviewItems.forEach { item -> put(item.toJson()) } })
    }.toString(2)

    fun decode(raw: String): CollectionBackupSnapshot {
        val root = runCatching { JSONObject(raw) }.getOrElse { throw IllegalArgumentException("Backup is not valid JSON") }
        require(root.optString("format") == FORMAT) { "Not a CardDex collection backup" }
        require(root.optInt("version", -1) == VERSION) { "Unsupported backup version" }
        val snapshot = CollectionBackupSnapshot(
            catalogVersion = root.requirePositiveInt("catalogVersion"),
            locations = root.requireArray("locations").objects().map { value ->
                LocationEntity(
                    id = value.requirePositiveLong("id"), name = value.requireText("name"),
                    type = value.requireText("type"), color = value.requireLong("color")
                )
            },
            allocations = root.requireArray("allocations").objects().map { value ->
                AllocationEntity(
                    id = value.requirePositiveLong("id"), cardId = value.requireText("cardId"),
                    variantId = value.optionalText("variantId"), variantDisplayName = value.requireText("variantDisplayName"),
                    variantResolution = value.requireText("variantResolution"), locationId = value.requirePositiveLong("locationId"),
                    status = value.requireText("status"), quantity = value.requirePositiveInt("quantity"),
                    updatedAt = value.requireLong("updatedAt")
                )
            },
            sessions = root.requireArray("sessions").objects().map { value ->
                ScanSessionEntity(
                    id = value.requirePositiveLong("id"), name = value.requireText("name"),
                    destinationId = value.requirePositiveLong("destinationId"), startedAt = value.requireLong("startedAt"),
                    endedAt = value.optionalLong("endedAt")
                )
            },
            events = root.requireArray("events").objects().map { value ->
                ScanEventEntity(
                    id = value.requirePositiveLong("id"), sessionId = value.requirePositiveLong("sessionId"),
                    cardId = value.requireText("cardId"), variantId = value.optionalText("variantId"),
                    variantDisplayName = value.requireText("variantDisplayName"), quantity = value.requirePositiveInt("quantity"),
                    confidence = value.requireDouble("confidence").toFloat().coerceIn(0f, 1f),
                    createdAt = value.requireLong("createdAt")
                )
            },
            reviewItems = root.requireArray("reviewItems").objects().map { value ->
                ReviewItemEntity(
                    id = value.requirePositiveLong("id"), sessionId = value.optionalLong("sessionId"),
                    imagePath = "", candidateIds = value.optString("candidateIds"),
                    note = value.requireText("note") + " (image is not included in backups)",
                    resolved = value.optBoolean("resolved", false), createdAt = value.requireLong("createdAt")
                )
            }
        )
        validate(snapshot)
        return snapshot
    }

    private fun validate(snapshot: CollectionBackupSnapshot) {
        require(snapshot.locations.isNotEmpty()) { "Backup has no storage locations" }
        requireUnique(snapshot.locations.map { it.id }, "location")
        requireUnique(snapshot.allocations.map { it.id }, "allocation")
        requireUnique(snapshot.sessions.map { it.id }, "session")
        requireUnique(snapshot.events.map { it.id }, "scan event")
        requireUnique(snapshot.reviewItems.map { it.id }, "review item")
        val locations = snapshot.locations.map { it.id }.toSet()
        val sessions = snapshot.sessions.map { it.id }.toSet()
        require(snapshot.allocations.all { it.locationId in locations }) { "Allocation references a missing location" }
        require(snapshot.sessions.all { it.destinationId in locations }) { "Session references a missing location" }
        require(snapshot.events.all { it.sessionId in sessions }) { "Scan event references a missing session" }
        require(snapshot.reviewItems.all { it.sessionId == null || it.sessionId in sessions }) { "Review item references a missing session" }
    }

    private fun requireUnique(values: List<Long>, label: String) {
        require(values.size == values.toSet().size) { "Duplicate $label ID" }
    }

    private fun LocationEntity.toJson() = JSONObject().apply {
        put("id", id); put("name", name); put("type", type); put("color", color)
    }

    private fun AllocationEntity.toJson() = JSONObject().apply {
        put("id", id); put("cardId", cardId); putNullable("variantId", variantId)
        put("variantDisplayName", variantDisplayName); put("variantResolution", variantResolution)
        put("locationId", locationId); put("status", status); put("quantity", quantity); put("updatedAt", updatedAt)
    }

    private fun ScanSessionEntity.toJson() = JSONObject().apply {
        put("id", id); put("name", name); put("destinationId", destinationId); put("startedAt", startedAt); putNullable("endedAt", endedAt)
    }

    private fun ScanEventEntity.toJson() = JSONObject().apply {
        put("id", id); put("sessionId", sessionId); put("cardId", cardId); putNullable("variantId", variantId)
        put("variantDisplayName", variantDisplayName); put("quantity", quantity); put("confidence", confidence.toDouble()); put("createdAt", createdAt)
    }

    private fun ReviewItemEntity.toJson() = JSONObject().apply {
        put("id", id); putNullable("sessionId", sessionId); put("candidateIds", candidateIds)
        put("note", note); put("resolved", resolved); put("createdAt", createdAt)
    }

    private fun JSONObject.putNullable(key: String, value: Any?) { put(key, value ?: JSONObject.NULL) }
    private fun JSONObject.requireArray(key: String) = optJSONArray(key) ?: throw IllegalArgumentException("Missing $key")
    private fun JSONArray.objects() = (0 until length()).map { index -> optJSONObject(index) ?: throw IllegalArgumentException("Invalid list item") }
    private fun JSONObject.requireText(key: String) = optString(key).trim().takeIf { it.isNotEmpty() } ?: throw IllegalArgumentException("Missing $key")
    private fun JSONObject.optionalText(key: String) = if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
    private fun JSONObject.requireLong(key: String) = if (has(key) && !isNull(key)) getLong(key) else throw IllegalArgumentException("Missing $key")
    private fun JSONObject.requirePositiveLong(key: String) = requireLong(key).also { require(it > 0) { "$key must be positive" } }
    private fun JSONObject.requirePositiveInt(key: String) = requireLong(key).toInt().also { require(it > 0) { "$key must be positive" } }
    private fun JSONObject.requireDouble(key: String) = if (has(key) && !isNull(key)) getDouble(key) else throw IllegalArgumentException("Missing $key")
    private fun JSONObject.optionalLong(key: String) = if (isNull(key)) null else requireLong(key)
}
