package com.lateral

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.lateral.agent.ManagedAgentController
import com.lateral.agent.ManagedAgentSnapshot

/** Development flavor's managed terminal, with explicit per-job automation approval. */
object AgentTerminalPanel {
    fun show(
        activity: Activity,
        onModalVisibilityChanged: (Boolean) -> Unit = {},
        onDialogWindowCreated: (android.app.Dialog) -> Unit = {},
        onDialogWindowDismissed: (android.app.Dialog) -> Unit = {},
    ) {
        if (activity.isFinishing || activity.isDestroyed) return
        val controller = ManagedAgentController.get(activity)
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val shell = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(12))
            setBackgroundColor(Color.rgb(9, 10, 11))
        }
        fun text(value: String): TextView = TextView(activity).apply {
            text = value; setTextColor(Color.rgb(190, 201, 200)); typeface = Typeface.MONOSPACE
            setPadding(0, dp(6), 0, dp(6)); setTextIsSelectable(true)
        }
        fun field(hintText: String, value: String = ""): EditText = EditText(activity).apply {
            hint = hintText; setText(value); setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY); typeface = Typeface.MONOSPACE
        }.also { shell.addView(it, LinearLayout.LayoutParams(-1, -2)) }
        fun button(label: String, action: () -> Unit): Button = Button(activity).apply {
            text = label; typeface = Typeface.MONOSPACE; setOnClickListener { action() }
        }.also { shell.addView(it, LinearLayout.LayoutParams(-1, dp(48))) }

        shell.addView(text("AGENT TERMINAL"))
        shell.addView(text("Use the existing Termux installation. First open Termux once, then set allow-external-apps=true in ~/.termux/termux.properties and run termux-reload-settings. Grant LATERAL_ permission to run commands in Termux when prompted."))
        button("Open Termux / setup") { controller.openTermuxApp(activity) }
        val install = button("Install / connect") { controller.install(activity) }
        shell.addView(text("Setup downloads development tools and installs the bundled agent runtime. Keep Termux running during setup."))
        button("Open terminal") { controller.openTerminal(activity) }
        button("Sign in to Codex") { controller.openTerminal(activity, signIn = true) }
        button("Refresh status") { controller.refresh() }
        val status = text("Connecting…").also { shell.addView(it) }
        val localModel = CheckBox(activity).apply {
            text = "Use a local model through Codex + LM Studio"
            setTextColor(Color.WHITE)
        }.also { shell.addView(it) }
        val model = field("Loaded LM Studio model identifier")
        val modelUrl = field("LM Studio URL, e.g. http://10.0.2.2:1234/v1")
        val saveModel = button("Save model settings") {
            controller.configureModel(localModel.isChecked, model.text.toString(), modelUrl.text.toString())
        }
        val adb = field("ADB device serial")
        val selectAdb = button("Select ADB device") { controller.selectAdb(adb.text.toString()) }
        shell.addView(text("For a physical device, enable Developer options → Wireless debugging. In the terminal run adb pair IP:PAIR_PORT, enter the pairing code, then adb connect IP:DEBUG_PORT. Refresh and select the connected serial shown below. Pairing and debug ports are different."))
        val devices = text("").also { shell.addView(it) }
        val project = field("Managed project name", "my-app").apply { isSingleLine = true }
        val objective = field("What should the agent build or fix?").apply { minLines = 3 }
        val approved = CheckBox(activity).apply {
            text = "Allow this job to edit/build the named project and install its debug APK on the selected device"
            setTextColor(Color.WHITE)
        }.also { shell.addView(it) }
        val start = button("Start agent") {
            controller.start(project.text.toString(), objective.text.toString())
            approved.isChecked = false
        }
        val demo = button("Run repair demo") {
            controller.start(project.text.toString(), "Run the bundled Android repair demonstration", demo = true)
            approved.isChecked = false
        }
        val cancel = button("Cancel job") { controller.cancel() }
        val output = text("No job output yet").also { shell.addView(it) }
        var dismissed = false
        var settingsLoaded = false
        lateinit var render: (ManagedAgentSnapshot) -> Unit
        render = { snapshot ->
            val health = snapshot.health
            val codex = health?.optJSONObject("codex")
            val adbHealth = health?.optJSONObject("adb")
            if (health != null && !settingsLoaded) {
                val settings = health.optJSONObject("settings")
                localModel.isChecked = settings?.optString("codexMode") == "oss-lmstudio"
                model.setText(settings?.optString("model", "").orEmpty())
                modelUrl.setText(settings?.optString("lmstudioBaseUrl", "").orEmpty())
                settingsLoaded = true
            }
            status.text = buildString {
                append(snapshot.status)
                if (health != null) {
                    append("\nCodex: ").append(codex?.optString("version", "unknown"))
                    append(" / ").append(codex?.optString("authStatus", "unavailable"))
                    append("\nADB: ").append(adbHealth?.optString("status", "unavailable"))
                    append("\nSelected device: ").append(adbHealth?.optString("serial", "none"))
                    append("\nProjects: ").append(health.optString("projectsRoot"))
                }
                snapshot.projectRoot?.let { append("\nProject: ").append(it) }
                snapshot.jobId?.let { append("\nJob: ").append(it).append(" / ").append(snapshot.jobStatus) }
            }
            val available = adbHealth?.optJSONArray("devices")
            devices.text = "ADB devices:\n" + (available?.toString(2) ?: "Refresh after pairing")
            if (!adb.hasFocus() && adb.text.isBlank() && adbHealth != null && !adbHealth.isNull("serial")) adb.setText(adbHealth.optString("serial"))
            val ready = health != null && codex?.optBoolean("ready", false) == true
            saveModel.isEnabled = !snapshot.busy && health != null
            localModel.isEnabled = !snapshot.busy
            model.isEnabled = !snapshot.busy
            modelUrl.isEnabled = !snapshot.busy
            install.isEnabled = !snapshot.busy
            selectAdb.isEnabled = !snapshot.busy && health != null
            start.isEnabled = !snapshot.busy && ready && approved.isChecked
            demo.isEnabled = !snapshot.busy && ready && approved.isChecked
            project.isEnabled = !snapshot.busy
            objective.isEnabled = !snapshot.busy
            approved.isEnabled = !snapshot.busy
            cancel.isEnabled = snapshot.busy && snapshot.jobId != null && !snapshot.cancelling
            cancel.text = if (snapshot.cancelling) "Waiting for cancellation…" else "Cancel job"
            output.text = snapshot.output.ifBlank { "No job output yet" }
        }
        approved.setOnCheckedChangeListener { _, _ -> render(controller.snapshot()) }
        var dialog: AlertDialog? = null
        val lease = TransientPanelCoordinator.claim(
            activity.display?.displayId ?: android.view.Display.DEFAULT_DISPLAY,
            TransientPanelCoordinator.Kind.CONTROLLER,
        ) { dialog?.dismiss() }
        val listener: (ManagedAgentSnapshot) -> Unit = { snapshot ->
            activity.runOnUiThread { if (!dismissed && !activity.isFinishing && !activity.isDestroyed) render(snapshot) }
        }
        controller.addListener(listener)
        try {
            val created = AlertDialog.Builder(activity)
                .setView(ScrollView(activity).apply { addView(shell, ViewGroup.LayoutParams(-1, -2)) })
                .setNegativeButton("Close", null).create()
            dialog = created
            created.setOnDismissListener {
                dismissed = true
                onDialogWindowDismissed(created)
                controller.removeListener(listener)
                lease.release()
                onModalVisibilityChanged(false)
            }
            created.show()
            onDialogWindowCreated(created)
            onModalVisibilityChanged(true)
            controller.refresh()
        } catch (error: RuntimeException) {
            dismissed = true; controller.removeListener(listener); lease.release()
            android.widget.Toast.makeText(activity, error.message ?: "Could not open Agent Terminal", android.widget.Toast.LENGTH_LONG).show()
        }
    }
}
