package net.inkyquill.pocketeditor.source

/** Provider capabilities describe the selection steps; the router is keyed by stable IDs. */
data class SourceProvider(val id: String, val title: String, val containerLabel: String? = null, val available: Boolean = false)

val sourceProviders = listOf(
    SourceProvider("disk", "Яндекс Диск", available = true),
    SourceProvider("edda", "Open Edda", "Проект", available = true),
    SourceProvider("seafile", "Seafile", "Библиотека"),
    SourceProvider("dropbox", "Dropbox"),
    SourceProvider("onedrive", "OneDrive"),
    SourceProvider("box", "Box"),
    SourceProvider("gdrive", "Google Drive"),
)
