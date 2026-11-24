package ai.opencode.server.model

data class OpenCodeProjectFile(val path: String, val isDirectory: Boolean) {
    val name: String get() = path.trimEnd('/').substringAfterLast('/')
}

data class OpenCodeProjectFiles(val directory: String, val entries: List<OpenCodeProjectFile>)

object ProjectFilePaths {
    private val drive = Regex("^[A-Za-z]:[/\\\\]")
    private fun windows(path: String) = drive.containsMatchIn(path) || path.startsWith("\\\\") || path.startsWith("//")

    fun normalize(path: String): String? {
        val value = if (windows(path)) path.replace('\\', '/') else path
        val prefix: String
        val rest: String
        when {
            drive.containsMatchIn(value) -> {
                prefix = value.take(3)
                rest = value.drop(3)
            }
            value.startsWith("//") -> {
                val parts = value.drop(2).split('/').filter { it.isNotEmpty() }
                if (parts.size < 2) return null
                prefix = "//${parts[0]}/${parts[1]}/"
                rest = parts.drop(2).joinToString("/")
            }
            value.startsWith('/') -> { prefix = "/"; rest = value.drop(1) }
            else -> return null
        }
        val parts = ArrayList<String>()
        for (part in rest.split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> if (parts.isEmpty()) return null else parts.removeAt(parts.lastIndex)
                else -> parts += part
            }
        }
        return prefix + parts.joinToString("/")
    }

    fun resolve(base: String, path: String): String? {
        val normalizedBase = normalize(base) ?: return null
        val value = if (windows(base)) path.replace('\\', '/') else path
        return if (value.startsWith('/') || windows(value)) normalize(value)
        else normalize("${normalizedBase.trimEnd('/')}/$value")
    }

    fun relative(root: String, path: String): String? {
        val base = normalize(root) ?: return null
        val value = normalize(path) ?: return null
        val ignoreCase = windows(base)
        if (base.equals(value, ignoreCase)) return ""
        val prefix = base.trimEnd('/') + "/"
        return if (value.startsWith(prefix, ignoreCase)) value.drop(prefix.length) else null
    }

    fun parent(path: String): String? {
        val value = normalize(path) ?: return null
        val slash = value.trimEnd('/').lastIndexOf('/')
        if (slash < 0) return null
        return normalize(value.substring(0, slash + 1))?.takeIf { it != value }
    }
}
