package com.pokemontcgscanner.app.data

data class CatalogUpdateMetadata(
    val catalogVersion: Int,
    val schemaVersion: Int,
    val language: String,
    val databaseFile: String,
    val databaseSha256: String,
    val cardCount: Int,
    val variantCount: Int
)

object CatalogUpdatePolicy {
    const val SUPPORTED_SCHEMA_VERSION = 2

    fun validate(metadata: CatalogUpdateMetadata, installedVersion: Int) {
        require(metadata.catalogVersion > installedVersion) { "Catalogue update is not newer than the installed version" }
        require(metadata.schemaVersion == SUPPORTED_SCHEMA_VERSION) { "Unsupported catalogue schema ${metadata.schemaVersion}" }
        require(metadata.language == "en") { "Only the English physical catalogue is supported" }
        require(Regex("catalog-en-v\\d+\\.sqlite").matches(metadata.databaseFile)) { "Unexpected catalogue database filename" }
        require(metadata.databaseSha256.matches(Regex("[a-f0-9]{64}"))) { "Invalid catalogue checksum" }
        require(metadata.cardCount > 0 && metadata.variantCount >= metadata.cardCount) { "Invalid catalogue counts" }
    }
}
