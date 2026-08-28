package com.pokemontcgscanner.app.export

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.pokemontcgscanner.app.CollectionSnapshot
import java.io.File

object ExportService {
    fun share(context: Context, snapshot: CollectionSnapshot) {
        val folder = File(context.cacheDir, "exports").apply { mkdirs() }
        val csv = File(folder, "carddex_collection.csv")
        val json = File(folder, "carddex_collection.json")
        val cardById = snapshot.cards.associateBy { it.id }
        val locationById = snapshot.locations.associateBy { it.id }
        csv.writeText(buildString {
            appendLine("card_id,name,set,number,variant,quantity,location,status")
            snapshot.allocations.forEach { row ->
                val card = cardById[row.cardId]
                appendLine(listOf(row.cardId, card?.name, card?.setName, card?.collectorNumber, row.variant, row.quantity, locationById[row.locationId]?.name, row.status).joinToString(",") { "\"${it.toString().replace("\"", "\"\"")}\"" })
            }
        })
        json.writeText(buildString {
            append("{\"exportedAt\":${System.currentTimeMillis()},\"inventory\":[")
            snapshot.allocations.forEachIndexed { index, row ->
                if (index > 0) append(',')
                append("{\"cardId\":\"${row.cardId}\",\"variant\":\"${row.variant}\",\"quantity\":${row.quantity},\"locationId\":${row.locationId},\"status\":\"${row.status}\"}")
            }
            append("]}")
        })
        val uris = arrayListOf(csv, json).mapTo(arrayListOf()) { FileProvider.getUriForFile(context, "${context.packageName}.files", it) }
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"; putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Export collection"))
    }
}
