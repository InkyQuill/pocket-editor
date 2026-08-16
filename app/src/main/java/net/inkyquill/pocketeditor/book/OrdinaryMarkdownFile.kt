package net.inkyquill.pocketeditor.book

internal fun String.isOrdinaryMarkdownFile(): Boolean =
    endsWith(".md", ignoreCase = false) && !startsWith('.') && '/' !in this && '\\' !in this
