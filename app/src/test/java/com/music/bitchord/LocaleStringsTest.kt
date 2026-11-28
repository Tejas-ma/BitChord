package com.music.bitchord

import com.music.bitchord.ui.components.SUPPORTED_LANGUAGES
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Every language the app offers must translate every key in values/strings.xml. A missing key
 * doesn't crash, it silently falls back to English mid-screen, so this catches it at test time.
 */
@RunWith(Parameterized::class)
class LocaleStringsTest(private val tag: String) {

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun locales(): List<String> = SUPPORTED_LANGUAGES.map { it.tag }.filter { it != "en" }

        internal val resDir: File by lazy {
            val projectRoot = File(".").canonicalFile
            if (File(projectRoot, "app/src/main/res").exists()) {
                File(projectRoot, "app/src/main/res")
            } else {
                File(projectRoot, "src/main/res")
            }
        }

        internal fun parse(file: File): Document =
            DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)

        /** Keys that need translating: `translatable="false"` entries are excluded. */
        internal fun translatableKeys(doc: Document): Set<String> {
            val keys = mutableSetOf<String>()
            for (tagName in listOf("string", "plurals", "string-array")) {
                val nodes = doc.getElementsByTagName(tagName)
                for (i in 0 until nodes.length) {
                    val element = nodes.item(i) as Element
                    if (element.getAttribute("translatable") == "false") continue
                    val name = element.getAttribute("name")
                    if (name.isNotEmpty()) keys.add(name)
                }
            }
            return keys
        }

        internal val baseKeys: Set<String> by lazy {
            translatableKeys(parse(File(resDir, "values/strings.xml")))
        }

        /**
         * App language tags are BCP-47 (`zh-CN` / `zh-TW`) while Android
         * resource directories use `-r` regions (`values-zh`, `values-zh-rTW`).
         * Mirrors what Android resolves at runtime: Simplified is the bare
         * `zh` catalogue, Traditional is `zh-rTW`.
         */
        internal fun tagToQualifier(tag: String): String = when (tag) {
            "zh-CN" -> "zh"
            "zh-TW" -> "zh-rTW"
            else -> tag
        }
    }

    @Test
    fun strings_containsAllBaseKeys() {
        val qualifier = tagToQualifier(tag)
        val localeFile = File(resDir, "values-$qualifier/strings.xml")
        assertTrue("values-$qualifier/strings.xml must exist (for language tag $tag)", localeFile.exists())

        val missingKeys = baseKeys - translatableKeys(parse(localeFile))
        assertTrue(
            "values-$qualifier/strings.xml (language $tag) is missing ${missingKeys.size} keys from values/strings.xml: $missingKeys",
            missingKeys.isEmpty(),
        )
    }
}

/**
 * The same completeness check, over every translated catalogue on disk rather
 * than only the languages the picker offers.
 *
 * `values-zh-rHK` and `values-b+zh+Hant` are what Android resolves for a device
 * set to those locales, but neither is in [SUPPORTED_LANGUAGES], so the
 * parameterised test above never read them — and both were six keys short,
 * which is a Hong Kong or Traditional-Chinese device seeing those settings fall
 * back to a different script mid-screen. Walking the directories keeps a future
 * catalogue from shipping half translated the same way.
 */
class AllLocaleCataloguesTest {

    @Test
    fun everyTranslatedCatalogueHasAllBaseKeys() {
        val resDir = LocaleStringsTest.resDir
        val catalogues = resDir.listFiles { file ->
            file.isDirectory && file.name.startsWith("values-") && File(file, "strings.xml").isFile
        }.orEmpty().sortedBy { it.name }

        assertTrue("no translated catalogues found under $resDir", catalogues.isNotEmpty())

        val incomplete = catalogues.mapNotNull { dir ->
            val missing = LocaleStringsTest.baseKeys -
                LocaleStringsTest.translatableKeys(LocaleStringsTest.parse(File(dir, "strings.xml")))
            if (missing.isEmpty()) null else "${dir.name}: ${missing.sorted()}"
        }
        assertTrue(
            "translated catalogues are missing keys from values/strings.xml:\n${incomplete.joinToString("\n")}",
            incomplete.isEmpty(),
        )
    }
}
