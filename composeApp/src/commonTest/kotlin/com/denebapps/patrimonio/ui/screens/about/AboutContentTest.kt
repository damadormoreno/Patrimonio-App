package com.denebapps.patrimonio.ui.screens.about

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AboutContentTest {
    private fun content(privacyPolicyUrl: String? = null, contactEmail: String? = null) =
        aboutContent(versionName = "1.2.3", privacyPolicyUrl = privacyPolicyUrl, contactEmail = contactEmail)

    @Test
    fun `the content names the app and its version`() {
        val content = content()

        assertEquals("Patrimonio", content.appName)
        assertEquals("1.2.3", content.versionName)
    }

    @Test
    fun `the data sources name every provider the app queries`() {
        val names = content().dataSources.map { it.name }

        assertEquals(listOf("Morningstar", "Yahoo Finance", "CoinGecko", "Frankfurter"), names)
    }

    @Test
    fun `price sources warn they are unofficial and may be late or fail`() {
        val sources = content().dataSources.filter { it.name == "Morningstar" || it.name == "Yahoo Finance" }

        assertEquals(2, sources.size)
        sources.forEach { source ->
            assertTrue("no oficial" in source.note, "${source.name}: ${source.note}")
            assertTrue("retrasarse" in source.note, "${source.name}: ${source.note}")
        }
    }

    @Test
    fun `crypto prices carry the CoinGecko attribution and link`() {
        val coinGecko = content().dataSources.single { it.name == "CoinGecko" }

        assertTrue("Powered by CoinGecko" in coinGecko.note)
        assertEquals("Criptomonedas", coinGecko.covers)
        assertEquals("https://www.coingecko.com", coinGecko.url)
    }

    @Test
    fun `exchange rates are credited to Frankfurter and the ECB`() {
        val frankfurter = content().dataSources.single { it.name == "Frankfurter" }

        assertEquals("Tipos de cambio", frankfurter.covers)
        assertTrue("BCE" in frankfurter.note)
    }

    @Test
    fun `the fonts are listed under the SIL Open Font License with their copyright notice`() {
        val fonts = content().licenses.filter { it.name == "Manrope" || it.name == "Instrument Serif" }

        assertEquals(2, fonts.size)
        fonts.forEach { font ->
            assertEquals("SIL Open Font License 1.1", font.license)
            val notice = assertNotNull(font.notice)
            assertTrue(notice.startsWith("Copyright"), notice)
        }
    }

    @Test
    fun `the open-source list covers the main libraries and the rumbo credit`() {
        val licenses = content().licenses.associate { it.name to it.license }

        assertEquals("Apache License 2.0", licenses["Koin"])
        assertEquals("Apache License 2.0", licenses["Ktor"])
        assertEquals("MIT License", licenses["FileKit"])
        assertEquals("MIT License", licenses["rumbo"])
        assertTrue(licenses.keys.containsAll(listOf("Kotlin", "Compose Multiplatform", "Room", "DataStore")))
    }

    @Test
    fun `every license entry names its license`() {
        content().licenses.forEach { entry ->
            assertTrue(entry.name.isNotBlank() && entry.license.isNotBlank(), "$entry")
        }
    }

    @Test
    fun `no privacy or contact link is offered while they are not configured`() {
        val content = content(privacyPolicyUrl = null, contactEmail = null)

        assertTrue(content.links.isEmpty())
    }

    @Test
    fun `the shipped configuration offers neither link until it is filled in`() {
        assertNull(PRIVACY_POLICY_URL)
        assertNull(CONTACT_EMAIL)
    }

    @Test
    fun `a configured privacy policy shows its row with the URL`() {
        val links = content(privacyPolicyUrl = "https://example.com/privacy").links

        val link = links.single()
        assertEquals("Política de privacidad", link.title)
        assertEquals("https://example.com/privacy", link.uri)
    }

    @Test
    fun `a configured contact email opens a mailto link`() {
        val links = content(contactEmail = "hola@example.com").links

        val link = links.single()
        assertEquals("Contacto", link.title)
        assertEquals("hola@example.com", link.subtitle)
        assertEquals("mailto:hola@example.com", link.uri)
    }

    @Test
    fun `privacy comes before contact when both are configured`() {
        val links = content("https://example.com/privacy", "hola@example.com").links

        assertEquals(listOf("Política de privacidad", "Contacto"), links.map { it.title })
    }

    @Test
    fun `blank configuration counts as not configured`() {
        val content = content(privacyPolicyUrl = "  ", contactEmail = "")

        assertTrue(content.links.isEmpty())
    }
}
