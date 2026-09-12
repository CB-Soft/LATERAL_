package com.lateral.agent

import java.net.URI

/** Pure boundary rules shared by the native controller and its tests. */
object ManagedAgentProtocol {
    const val BASE_URL = "http://127.0.0.1:8766"
    const val HOME = "/data/data/com.termux/files/home"
    const val RUNTIME = "$HOME/lateral-agent"
    const val PERMISSION = "com.termux.permission.RUN_COMMAND"

    fun validateProjectName(name: String): String {
        val value = name.trim()
        require(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}").matches(value)) {
            "Use 1–64 letters, numbers, underscores or hyphens for the project name"
        }
        return value
    }

    fun validateProjectRoot(root: String, projectsRoot: String): String {
        require(projectsRoot.startsWith('/') && projectsRoot != "/") { "Missing managed projects root" }
        val base = projectsRoot.trimEnd('/')
        require(root.startsWith("$base/") && root.removePrefix("$base/").let {
            it.isNotEmpty() && it.split('/').none { segment -> segment.isEmpty() || segment == "." || segment == ".." }
        }) { "The service returned a project outside its managed projects directory" }
        return root
    }

    fun validateJobId(id: String): String {
        require(Regex("[A-Za-z0-9_-]{1,100}").matches(id)) { "Invalid job identifier" }
        return id
    }

    fun isTerminal(status: String): Boolean = status in setOf("COMPLETED", "FAILED", "CANCELLED")

    fun validatedEndpoint(path: String): URI {
        require(path.startsWith('/') && !path.startsWith("//") && !path.contains('#')) { "Invalid service path" }
        val uri = URI(BASE_URL + path)
        require(uri.host == "127.0.0.1" && uri.port == 8766 && uri.userInfo == null) { "Invalid local service endpoint" }
        return uri
    }
}
