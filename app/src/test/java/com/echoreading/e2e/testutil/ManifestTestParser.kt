package com.echoreading.e2e.testutil

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Standard XML DOM parser for AndroidManifest.xml and resource XML files.
 * Used for opaque-box contract verification without requiring Android runtime instrumentation.
 */
object ManifestTestParser {

    private fun findProjectRoot(): File {
        var dir = File(".").canonicalFile
        while (dir.parentFile != null) {
            if (File(dir, "app/src/main/AndroidManifest.xml").isFile) {
                return dir
            }
            dir = dir.parentFile
        }
        return File(".").canonicalFile
    }

    val manifestFile: File by lazy {
        File(findProjectRoot(), "app/src/main/AndroidManifest.xml")
    }

    val stringsFile: File by lazy {
        File(findProjectRoot(), "app/src/main/res/values/strings.xml")
    }

    val themesFile: File by lazy {
        File(findProjectRoot(), "app/src/main/res/values/themes.xml")
    }

    data class IntentFilterData(
        val actions: List<String>,
        val categories: List<String>,
        val mimeTypes: List<String>,
        val label: String?
    )

    data class ActivityData(
        val name: String,
        val label: String?,
        val theme: String?,
        val excludeFromRecents: Boolean,
        val launchMode: String?,
        val intentFilters: List<IntentFilterData>
    )

    fun parseActivities(): List<ActivityData> {
        if (!manifestFile.isFile) return emptyList()
        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(manifestFile)
        val activities = mutableListOf<ActivityData>()

        val activityNodes = doc.getElementsByTagName("activity")
        for (i in 0 until activityNodes.length) {
            val el = activityNodes.item(i) as? Element ?: continue
            val name = el.getAttribute("android:name")
            val label = el.getAttribute("android:label").ifEmpty { null }
            val theme = el.getAttribute("android:theme").ifEmpty { null }
            val excludeFromRecents = el.getAttribute("android:excludeFromRecents") == "true"
            val launchMode = el.getAttribute("android:launchMode").ifEmpty { null }

            val filterList = mutableListOf<IntentFilterData>()
            val filterNodes = el.getElementsByTagName("intent-filter")
            for (j in 0 until filterNodes.length) {
                val filterEl = filterNodes.item(j) as? Element ?: continue
                val filterLabel = filterEl.getAttribute("android:label").ifEmpty { null }

                val actions = mutableListOf<String>()
                val categories = mutableListOf<String>()
                val mimeTypes = mutableListOf<String>()

                val children = filterEl.childNodes
                for (k in 0 until children.length) {
                    val child = children.item(k)
                    if (child.nodeType == Node.ELEMENT_NODE) {
                        val childEl = child as Element
                        when (childEl.tagName) {
                            "action" -> actions.add(childEl.getAttribute("android:name"))
                            "category" -> categories.add(childEl.getAttribute("android:name"))
                            "data" -> {
                                val mime = childEl.getAttribute("android:mimeType")
                                if (mime.isNotEmpty()) mimeTypes.add(mime)
                            }
                        }
                    }
                }
                filterList.add(IntentFilterData(actions, categories, mimeTypes, filterLabel))
            }

            activities.add(
                ActivityData(
                    name = name,
                    label = label,
                    theme = theme,
                    excludeFromRecents = excludeFromRecents,
                    launchMode = launchMode,
                    intentFilters = filterList
                )
            )
        }
        return activities
    }

    fun parseStrings(): Map<String, String> {
        if (!stringsFile.isFile) return emptyMap()
        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(stringsFile)
        val strings = mutableMapOf<String, String>()

        val stringNodes = doc.getElementsByTagName("string")
        for (i in 0 until stringNodes.length) {
            val el = stringNodes.item(i) as? Element ?: continue
            val name = el.getAttribute("name")
            val text = el.textContent
            strings[name] = text
        }
        return strings
    }

    fun parseThemeItems(styleName: String): Map<String, String> {
        if (!themesFile.isFile) return emptyMap()
        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(themesFile)
        val items = mutableMapOf<String, String>()

        val styleNodes = doc.getElementsByTagName("style")
        for (i in 0 until styleNodes.length) {
            val el = styleNodes.item(i) as? Element ?: continue
            if (el.getAttribute("name") == styleName) {
                val itemNodes = el.getElementsByTagName("item")
                for (j in 0 until itemNodes.length) {
                    val itemEl = itemNodes.item(j) as? Element ?: continue
                    val itemName = itemEl.getAttribute("name")
                    items[itemName] = itemEl.textContent
                }
            }
        }
        return items
    }
}
