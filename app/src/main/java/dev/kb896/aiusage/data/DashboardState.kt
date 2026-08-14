package dev.kb896.aiusage.data

import dev.kb896.aiusage.Panel

enum class Account(val slug: String, val label: String, val accent: Int) {
    PERSONAL("personal", "PERSONAL", Panel.Palette.PERSONAL),
    WORK("work", "WORK", Panel.Palette.WORK);

    companion object { fun fromSlug(s: String?) = entries.firstOrNull { it.slug == s } }
}
