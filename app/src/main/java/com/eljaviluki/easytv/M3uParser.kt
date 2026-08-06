package com.eljaviluki.easytv

object M3uParser {
    private val attrRegex = Regex("""([\w-]+)="([^"]*)"""")

    fun parse(content: String): List<CatalogItem> {
        val lines = content.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()

        val items = mutableListOf<CatalogItem>()
        var pendingName = "Canal"
        var pendingGroup = "Sin categoría"
        var pendingLogo: String? = null
        var number = 1

        for (line in lines) {
            when {
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    val attrs = attrRegex.findAll(line).associate { it.groupValues[1] to it.groupValues[2] }
                    pendingGroup = attrs["group-title"]?.ifBlank { "Sin categoría" } ?: "Sin categoría"
                    pendingLogo = attrs["tvg-logo"]?.ifBlank { null }
                    pendingName = line.substringAfterLast(',').trim().ifBlank {
                        attrs["tvg-name"] ?: "Canal"
                    }
                }
                line.startsWith("#") -> Unit
                else -> {
                    items += CatalogItem(
                        number = number++,
                        name = pendingName,
                        group = pendingGroup,
                        logo = pendingLogo,
                        url = line
                    )
                    pendingName = "Canal"
                    pendingGroup = "Sin categoría"
                    pendingLogo = null
                }
            }
        }
        return items
    }
}
