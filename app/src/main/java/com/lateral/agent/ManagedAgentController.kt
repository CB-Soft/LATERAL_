package com.lateral.agent

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class ManagedAgentSnapshot(
    val status: String = "Install or connect the Agent Terminal",
    val health: JSONObject? = null,
    val busy: Boolean = false,
    val jobId: String? = null,
    val jobStatus: String? = null,
    val projectRoot: String? = null,
    val cancelling: Boolean = false,
    val output: String = "",
)

/** Process-owned controller: closing a panel does not abandon an accepted job. */
class ManagedAgentController private constructor(context: Context) {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadScheduledExecutor { Thread(it, "lateral-agent-terminal").apply { isDaemon = true } }
    private val listeners = CopyOnWriteArrayList<(ManagedAgentSnapshot) -> Unit>()
    private val preferences = this.context.getSharedPreferences("managed-agent", Context.MODE_PRIVATE)
    private val token: String = loadToken()
    @Volatile private var state = ManagedAgentSnapshot()
    private var after = 0
    private var installPolls = 0
    @Volatile private var installGeneration = 0

    init {
        preferences.getString("activeJob", null)?.let { id ->
            if (runCatching { ManagedAgentProtocol.validateJobId(id) }.isSuccess) {
                state = state.copy(busy = true, jobId = id, jobStatus = "RUNNING", status = "Reconnecting to the active job")
                poll(id)
            }
        }
    }

    fun snapshot() = state
    fun addListener(listener: (ManagedAgentSnapshot) -> Unit) { listeners += listener; listener(state) }
    fun removeListener(listener: (ManagedAgentSnapshot) -> Unit) { listeners -= listener }

    private fun loadToken(): String {
        val file = File(context.noBackupFilesDir, "agent-bridge.token")
        if (file.exists()) {
            val saved = file.readText().trim()
            require(Regex("[a-f0-9]{64}").matches(saved)) { "Invalid local Agent Terminal credential" }
            return saved
        }
        val bytes = ByteArray(32).also(SecureRandom()::nextBytes)
        val generated = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
        file.writeText(generated)
        file.setReadable(false, false); file.setReadable(true, true)
        file.setWritable(false, false); file.setWritable(true, true)
        return generated
    }

    private fun update(transform: (ManagedAgentSnapshot) -> ManagedAgentSnapshot) {
        main.post { state = transform(state); listeners.forEach { it(state) } }
    }

    fun refresh() {
        executor.execute {
            runCatching { request("GET", "/v1/health") }.onSuccess { health ->
                update { it.copy(health = health, status = "Agent Terminal connected") }
            }.onFailure { error -> update { it.copy(health = null, status = "Service unavailable: ${error.message}. Use Install / connect, or check Termux.") } }
        }
    }

    fun install(activity: Activity) {
        if (state.busy) return
        if (!requirePermission(activity)) return
        state = state.copy(busy = true, status = "Installing runtime in Termux…")
        listeners.forEach { it(state) }
        executor.execute {
            val connected = runCatching { request("GET", "/v1/health") }.getOrNull()
            if (connected != null) {
                update { it.copy(busy = false, health = connected, status = "Agent Terminal connected") }
                return@execute
            }
            runCatching {
                val archive = context.assets.open("agent-runtime.tar.gz").use { it.readBytes() }
                require(archive.size <= 150_000) { "Bundled runtime exceeds safe intent size" }
                val encoded = Base64.encodeToString(archive, Base64.NO_WRAP)
                val script = """
                    set -eu
                    umask 077
                    mkdir -p '${ManagedAgentProtocol.RUNTIME}/source' '${ManagedAgentProtocol.RUNTIME}/state'
                    printf '%s' '$token' > '${ManagedAgentProtocol.RUNTIME}/state/bridge.token'
                    chmod 600 '${ManagedAgentProtocol.RUNTIME}/state/bridge.token'
                    printf '%s' '$encoded' | base64 -d | tar -xz -C '${ManagedAgentProtocol.RUNTIME}/source'
                    exec bash '${ManagedAgentProtocol.RUNTIME}/source/install.sh'
                """.trimIndent()
                main.post {
                    runCatching { runCommand(activity, "Install Agent Terminal", arrayOf("-s"), script, true) }
                        .onFailure { error -> update { it.copy(busy = false, status = "Could not start Termux: ${error.message}") } }
                        .onSuccess { installPolls = 0; checkInstall(++installGeneration) }
                }
            }.onFailure { error -> update { it.copy(busy = false, status = "Installation could not start: ${error.message}") } }
        }
    }

    private fun checkInstall(generation: Int) {
        executor.schedule({
            if (generation != installGeneration) return@schedule
            val result = runCatching { request("GET", "/v1/health") }
            if (result.isSuccess) {
                update { it.copy(busy = false, health = result.getOrThrow(), status = "Agent Terminal connected") }
            } else if (++installPolls >= 120) {
                update { it.copy(busy = false, status = "Setup is not connected yet. Open Termux to inspect setup, then Refresh. Enable allow-external-apps=true and RUN_COMMAND permission if no setup began.") }
            } else { checkInstall(generation) }
        }, 5, TimeUnit.SECONDS)
    }

    fun setupResult(exitCode: Int, error: String, transcript: String) {
        if (exitCode != 0 || error.isNotBlank()) {
            installGeneration++
            update { it.copy(busy = false, status = "Termux setup failed: ${error.ifBlank { "exit $exitCode" }}", output = transcript.takeLast(12000)) }
        } else {
            update { it.copy(status = "Runtime installed; connecting…", output = transcript.takeLast(12000)) }
            refresh()
        }
    }

    fun openTerminal(activity: Activity, signIn: Boolean = false) {
        if (!requirePermission(activity)) return
        val script = "exec '${ManagedAgentProtocol.RUNTIME}/bin/lateral-agent' " + if (signIn) "login" else "terminal"
        runCatching { runCommand(activity, if (signIn) "Sign in to Codex" else "Agent Terminal", arrayOf("-lc", script), null, false) }
            .onFailure { error -> update { it.copy(status = "Could not open terminal: ${error.message}") } }
    }

    fun openTermuxApp(activity: Activity) {
        val intent = activity.packageManager.getLaunchIntentForPackage("com.termux")
        if (intent == null) update { it.copy(status = "Install Termux from its official GitHub or F-Droid release, then open it once.") }
        else activity.startActivity(intent)
    }

    private fun requirePermission(activity: Activity): Boolean {
        if (activity.checkSelfPermission(ManagedAgentProtocol.PERMISSION) == PackageManager.PERMISSION_GRANTED) return true
        activity.requestPermissions(arrayOf(ManagedAgentProtocol.PERMISSION), 8766)
        update { it.copy(status = "Grant permission to run commands in Termux, then tap the action again. In Termux enable allow-external-apps=true.") }
        return false
    }

    private fun runCommand(activity: Activity, label: String, args: Array<String>, stdin: String?, background: Boolean) {
        val intent = Intent("com.termux.RUN_COMMAND").apply {
            setClassName("com.termux", "com.termux.app.RunCommandService")
            putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", args)
            putExtra("com.termux.RUN_COMMAND_WORKDIR", ManagedAgentProtocol.HOME)
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", background)
            putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
            putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL", label)
            putExtra("com.termux.RUN_COMMAND_BACKGROUND_CUSTOM_LOG_LEVEL", "0")
            if (stdin != null) putExtra("com.termux.RUN_COMMAND_STDIN", stdin)
            if (background) {
                val callback = Intent(activity, AgentSetupResultReceiver::class.java)
                val mutable = if (android.os.Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", PendingIntent.getBroadcast(
                    activity, 8766, callback, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_ONE_SHOT or mutable,
                ))
            }
        }
        check(activity.startService(intent) != null) { "Termux RUN_COMMAND service is unavailable" }
        if (!background) activity.packageManager.getLaunchIntentForPackage("com.termux")?.let(activity::startActivity)
    }

    fun selectAdb(serial: String) {
        if (state.busy) return
        val value = serial.trim()
        if (value.isEmpty() || value.any { it.isWhitespace() }) { update { it.copy(status = "Choose a device serial from the ADB list") }; return }
        executor.execute {
            runCatching { request("POST", "/v1/settings", JSONObject().put("adbSerial", value)) }
                .onSuccess { refresh() }
                .onFailure { error -> update { it.copy(status = "ADB selection failed: ${error.message}") } }
        }
    }

    fun configureModel(local: Boolean, model: String, baseUrl: String) {
        if (state.busy) return
        if (local && model.isBlank()) { update { it.copy(status = "Enter the loaded LM Studio model identifier") }; return }
        state = state.copy(busy = true, status = "Saving model settings…")
        listeners.forEach { it(state) }
        executor.execute {
            runCatching {
                val body = JSONObject().put("codexMode", if (local) "oss-lmstudio" else "chatgpt")
                if (local) body.put("model", model.trim()).put("lmstudioBaseUrl", baseUrl.trim())
                request("POST", "/v1/settings", body)
            }.onSuccess { update { it.copy(busy = false, status = "Model settings saved") }; refresh() }
                .onFailure { error -> update { it.copy(busy = false, status = "Model settings failed: ${error.message}") } }
        }
    }

    fun start(projectName: String, objective: String, demo: Boolean = false) {
        if (state.busy) return
        val name = runCatching { ManagedAgentProtocol.validateProjectName(projectName) }.getOrElse { error ->
            update { it.copy(status = error.message.orEmpty()) }; return
        }
        if (!demo && objective.isBlank()) { update { it.copy(status = "Enter a development objective") }; return }
        state = state.copy(busy = true, cancelling = false, output = "", status = "Requesting project and job…", jobId = null, jobStatus = null)
        listeners.forEach { it(state) }
        executor.execute {
            val id = UUID.randomUUID().toString()
            var submitted = false
            runCatching {
                val health = request("GET", "/v1/health")
                val root = if (demo) null else {
                    val projects = request("GET", "/v1/projects").getJSONArray("projects")
                    val existing = (0 until projects.length()).map { projects.getJSONObject(it) }.firstOrNull { it.getString("name") == name }
                    val project = existing ?: request("POST", "/v1/projects", JSONObject().put("name", name))
                    ManagedAgentProtocol.validateProjectRoot(project.getString("projectRoot"), health.getString("projectsRoot"))
                }
                val body = JSONObject().put("jobId", id).put("projectRoot", root)
                    .put("objective", objective.trim())
                    .put("grantedCapabilities", JSONArray(AgentPolicy.projectCapabilities.map { it.id }.filter { it != "project.git" }))
                submitted = true
                preferences.edit().putString("activeJob", id).apply()
                val accepted = request("POST", if (demo) "/v1/demo" else "/v1/jobs", body)
                require(accepted.getString("jobId") == id) { "Service returned an unexpected job identifier" }
                val actualRoot = ManagedAgentProtocol.validateProjectRoot(accepted.getString("projectRoot"), health.getString("projectsRoot"))
                preferences.edit().putString("activeJob", id).apply()
                after = 0
                update { it.copy(health = health, jobId = id, jobStatus = "RUNNING", projectRoot = actualRoot, status = "Job accepted") }
                poll(id)
            }.onFailure { error ->
                if (submitted && (error !is AgentHttpException || error.code >= 500)) {
                    update { it.copy(jobId = id, jobStatus = "RUNNING", status = "Submission response lost; reconnecting to determine the job outcome") }
                    after = 0
                    poll(id)
                } else {
                    preferences.edit().remove("activeJob").apply()
                    update { it.copy(busy = false, status = "Could not start job: ${error.message}") }
                }
            }
        }
    }

    fun cancel() {
        val id = state.jobId ?: return
        if (!state.busy || state.cancelling) return
        state = state.copy(cancelling = true, status = "Cancellation requested; waiting for the worker")
        listeners.forEach { it(state) }
        executor.execute {
            runCatching { request("POST", "/v1/jobs/$id/cancel", JSONObject()) }
                .onFailure { error -> update { it.copy(cancelling = false, status = "Cancellation not acknowledged: ${error.message}") } }
            // Continue polling. Only the service's terminal status releases the job.
        }
    }

    private fun poll(id: String) {
        executor.schedule({
            runCatching {
                    val response = request("GET", "/v1/jobs/$id/events?after=$after")
                    val status = response.getString("status").uppercase()
                    require(status in setOf("RUNNING", "QUEUED", "CANCELLING", "COMPLETED", "FAILED", "CANCELLED")) { "Unknown worker status: $status" }
                    val messages = buildList {
                        val events = response.optJSONArray("events") ?: JSONArray()
                        for (i in 0 until events.length()) {
                            val event = events.getJSONObject(i)
                            val sequence = event.getInt("sequence")
                            if (sequence > after) { add("[${event.optString("kind", "OUTPUT")}] ${event.optString("message")}"); after = sequence }
                        }
                    }.joinToString("\n")
                    val terminal = ManagedAgentProtocol.isTerminal(status)
                    if (terminal) preferences.edit().remove("activeJob").apply()
                    update { current -> current.copy(
                        busy = !terminal, cancelling = current.cancelling && !terminal, jobStatus = status,
                        status = "Job ${status.lowercase()}",
                        output = (current.output + if (messages.isEmpty()) "" else "\n$messages").takeLast(60000),
                    ) }
                    if (!terminal) poll(id)
                }.onFailure { error ->
                    update { it.copy(status = "Connection interrupted; job outcome unknown: ${error.message}. Reconnecting…") }
                    poll(id)
                }
        }, 1, TimeUnit.SECONDS)
    }

    private fun request(method: String, path: String, body: JSONObject? = null): JSONObject {
        val connection = ManagedAgentProtocol.validatedEndpoint(path).toURL().openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 2500
        connection.readTimeout = 20000
        connection.setRequestProperty("Authorization", "Bearer $token")
        connection.setRequestProperty("Accept", "application/json")
        return try {
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw AgentHttpException(code, "HTTP $code: ${text.take(300)}")
            JSONObject(text)
        } finally { connection.disconnect() }
    }

    companion object {
        @Volatile private var instance: ManagedAgentController? = null
        fun get(context: Context): ManagedAgentController = instance ?: synchronized(this) {
            instance ?: ManagedAgentController(context).also { instance = it }
        }
    }
}

private class AgentHttpException(val code: Int, message: String) : Exception(message)
