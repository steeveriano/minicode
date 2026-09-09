package com.danielealbano.androidremotecontrolmcp.resources

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Guards the Spanish translation against the way it will actually rot: someone adds an English
 * string and never adds its Spanish counterpart, so one screen silently turns bilingual.
 *
 * Parses the checked-in resource files directly — no Robolectric, no device — so a gap fails the
 * build rather than showing up on a phone.
 */
@DisplayName("String resource parity")
class StringResourceParityTest {
    @Test
    fun `every translatable string has a Spanish translation`() {
        val missing = (stringsIn(DEFAULT_STRINGS).keys - stringsIn(SPANISH_STRINGS).keys) - NOT_TRANSLATED

        assertTrue(missing.isEmpty()) {
            "Missing from $SPANISH_STRINGS: ${missing.sorted()}. Translate them, or add the name " +
                "to NOT_TRANSLATED if it must stay identical across locales."
        }
    }

    @Test
    fun `the Spanish file defines no string the default file lacks`() {
        val orphaned = stringsIn(SPANISH_STRINGS).keys - stringsIn(DEFAULT_STRINGS).keys

        assertTrue(orphaned.isEmpty()) {
            "Defined in $SPANISH_STRINGS but not in $DEFAULT_STRINGS: ${orphaned.sorted()}"
        }
    }

    @Test
    fun `translations keep the format specifiers of their originals`() {
        val default = stringsIn(DEFAULT_STRINGS)
        val spanish = stringsIn(SPANISH_STRINGS)

        // A dropped or renumbered specifier is a crash at format time, not a cosmetic slip.
        for ((name, translation) in spanish) {
            assertEquals(specifiersIn(default.getValue(name)), specifiersIn(translation)) {
                "Format specifiers differ for '$name'"
            }
        }
    }

    @Test
    fun `names that must stay identical across locales are absent from the translation`() {
        val spanish = stringsIn(SPANISH_STRINGS).keys

        // Listing one here and translating it anyway would defeat the point of the list.
        val translatedAnyway = NOT_TRANSLATED.filter { it in spanish }
        assertTrue(translatedAnyway.isEmpty()) {
            "These must fall back to the default locale but are translated: $translatedAnyway"
        }
    }

    private fun stringsIn(relativePath: String): Map<String, String> {
        val file =
            listOf(File(relativePath), File("app", relativePath)).firstOrNull { it.isFile }
                ?: error("Could not find $relativePath from ${File(".").absolutePath}")
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = document.getElementsByTagName("string")
        return (0 until nodes.length)
            .map { nodes.item(it) as Element }
            .associate { it.getAttribute("name") to it.textContent }
    }

    private fun specifiersIn(value: String): List<String> =
        SPECIFIER
            .findAll(value)
            .map { it.value }
            .sorted()
            .toList()

    companion object {
        const val DEFAULT_STRINGS = "src/main/res/values/strings.xml"
        const val SPANISH_STRINGS = "src/main/res/values-es/strings.xml"

        private val SPECIFIER = Regex("""%\d+\$[sd]""")


        /**
         * Names that MUST resolve to the default locale everywhere.
         *
         * Notification channel ids are persisted keys, and a per-locale value would strand the
         * channel a user already has. The author's name and handles are identity, not copy. The
         * cloudflared hint is a literal command-line flag.
         */
        private val NOT_TRANSLATED =
            setOf(
                "notification_channel_mcp_server_id",
                "about_author_name",
                "about_author_email",
                "about_author_linkedin",
                "about_author_x",
                "remote_access_cloudflare_extra_args_hint",
                "storage_location_description_counter",
            )
    }
}
