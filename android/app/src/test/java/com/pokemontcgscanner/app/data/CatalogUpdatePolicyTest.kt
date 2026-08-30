package com.pokemontcgscanner.app.data

import org.junit.Assert.assertThrows
import org.junit.Test

class CatalogUpdatePolicyTest {
    private val valid = CatalogUpdateMetadata(
        catalogVersion = 3, schemaVersion = 2, language = "en",
        databaseFile = "catalog-en-v3.sqlite", databaseSha256 = "a".repeat(64),
        cardCount = 21_000, variantCount = 38_000
    )

    @Test fun `newer compatible catalogue is accepted`() {
        CatalogUpdatePolicy.validate(valid, installedVersion = 2)
    }

    @Test fun `same version rollback and unsupported schema are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { CatalogUpdatePolicy.validate(valid.copy(catalogVersion = 2), 2) }
        assertThrows(IllegalArgumentException::class.java) { CatalogUpdatePolicy.validate(valid.copy(schemaVersion = 3), 2) }
        assertThrows(IllegalArgumentException::class.java) { CatalogUpdatePolicy.validate(valid.copy(databaseSha256 = "bad"), 2) }
    }
}
