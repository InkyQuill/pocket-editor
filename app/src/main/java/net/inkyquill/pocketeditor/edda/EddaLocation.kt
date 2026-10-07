package net.inkyquill.pocketeditor.edda

/** Opaque account key includes server and authenticated author; paths are project-relative. */
data class EddaLocation(val account: String, val project: String, val path: String = "") {
    init {
        require(account.matches(Regex("[a-f0-9]{64}")))
        require(project.matches(Regex("[A-Za-z0-9_-]+")))
        require(path.isEmpty() || path.split('/').all { it.isNotEmpty() && it != "." && it != ".." && '\\' !in it && it.none(Char::isISOControl) })
    }
    val root: String get() = "edda://$account/$project" + if (path.isEmpty()) "" else "/$path"
    fun child(name: String): EddaLocation {
        require(name.isNotEmpty() && '/' !in name)
        return copy(path = if (path.isEmpty()) name else "$path/$name")
    }
    companion object {
        fun parse(value: String): EddaLocation {
            require(value.startsWith("edda://"))
            val parts = value.removePrefix("edda://").trimEnd('/').split('/', limit = 3)
            require(parts.size >= 2)
            return EddaLocation(parts[0], parts[1], parts.getOrElse(2) { "" })
        }
    }
}
