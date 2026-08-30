package com.lateral

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.lateral.agent.AgentController
import com.lateral.agent.AgentJobStatus
import com.lateral.agent.AgentSnapshot

/** Phone-owned controller for the first LATERAL_ development-job vertical slice. */
object AgentPanel {
    fun show(
        activity: Activity,
        controller: AgentController,
        onModalVisibilityChanged: (Boolean) -> Unit = {},
    ) {
        if (activity.isFinishing || activity.isDestroyed) return
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        var dialog: android.app.AlertDialog? = null
        val lease = TransientPanelCoordinator.claim(
            activity.display?.displayId ?: android.view.Display.DEFAULT_DISPLAY,
            TransientPanelCoordinator.Kind.CONTROLLER,
        ) { dialog?.dismiss() }
        val shell = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(4))
            setBackgroundColor(Color.rgb(9, 10, 11))
        }
        fun text(value: String = "") = TextView(activity).apply {
            this.text = value
            typeface = Typeface.MONOSPACE
            setTextColor(Color.rgb(190, 201, 200))
        }
        fun button(label: String, action: () -> Unit) = Button(activity).apply {
            this.text = label
            typeface = Typeface.MONOSPACE
            setOnClickListener { action() }
        }

        val bridgeUrl = EditText(activity).apply {
            hint = "bridge URL (default: http://127.0.0.1:8765)"
            setText(controller.snapshot().bridgeUrl)
            isSingleLine = true
            typeface = Typeface.MONOSPACE
            setTextColor(Color.WHITE)
        }
        val projectRoot = EditText(activity).apply {
            hint = "approved project path"
            isSingleLine = true
            typeface = Typeface.MONOSPACE
            setTextColor(Color.WHITE)
        }
        val objective = EditText(activity).apply {
            hint = "development objective"
            minLines = 2
            gravity = Gravity.TOP
            typeface = Typeface.MONOSPACE
            setTextColor(Color.WHITE)
        }
        val status = text("bridge: not checked")
        val events = text("No active job")
        events.setTextColor(Color.rgb(150, 162, 166))
        val eventsScroll = ScrollView(activity).apply {
            addView(events)
        }
        lateinit var render: (AgentSnapshot) -> Unit
        lateinit var start: Button
        lateinit var cancel: Button
        val actions = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val approve = button("approve") {
            val root = projectRoot.text.toString().trim()
            if (root.isBlank()) {
                Toast.makeText(activity, "Enter a project path first", Toast.LENGTH_SHORT).show()
            } else {
                controller.grantProjectAutomation(root)
                render(controller.snapshot())
            }
        }
        start = button("start") {
            val root = projectRoot.text.toString().trim()
            if (!controller.start(objective.text.toString(), root)) {
                Toast.makeText(
                    activity,
                    "Approve project automation and ensure no job is running",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
        cancel = button("cancel") { controller.cancel() }
        actions.addView(approve, LinearLayout.LayoutParams(0, dp(48), 1f))
        actions.addView(start, LinearLayout.LayoutParams(0, dp(48), 1f))
        actions.addView(cancel, LinearLayout.LayoutParams(0, dp(48), 1f))

        render = { snapshot: AgentSnapshot ->
            val job = snapshot.job
            val granted = projectRoot.text.toString().trim().let(controller::hasProjectAutomation)
            status.text = buildString {
                append("bridge: ").append(snapshot.bridgeUrl)
                append("\nproject grant: ").append(if (granted) "APPROVED" else "NOT APPROVED")
                if (job != null) append("\njob: ").append(job.status.name.lowercase())
            }
            events.text = job?.events?.joinToString("\n") { event ->
                "[${event.kind.name.lowercase()}] ${event.message}"
            }?.ifBlank { "Job has no events" } ?: "No active job"
            start.isEnabled = job?.status != AgentJobStatus.RUNNING
            cancel.isEnabled = job?.status == AgentJobStatus.RUNNING
        }

        val connect = button("connect") {
            controller.setBridgeUrl(bridgeUrl.text.toString())
            status.text = "bridge: checking…"
            controller.probe { result ->
                activity.runOnUiThread {
                    status.text = result.fold(
                        onSuccess = { capabilities ->
                            "bridge: READY\nprovider: ${capabilities.displayName}\ncapabilities: " +
                                capabilities.capabilities.size
                        },
                        onFailure = { error -> "bridge: unavailable\n${error.message ?: "connection failed"}" },
                    )
                }
            }
        }

        shell.addView(text("LATERAL_ / agent"), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(38),
        ))
        shell.addView(bridgeUrl, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(52),
        ))
        shell.addView(connect, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(46),
        ))
        shell.addView(status, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(72),
        ))
        shell.addView(projectRoot, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(52),
        ))
        shell.addView(objective, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(86),
        ))
        shell.addView(actions, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(52),
        ))
        shell.addView(eventsScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(180),
        ))

        val listener: (AgentSnapshot) -> Unit = { snapshot ->
            activity.runOnUiThread {
                if (!activity.isFinishing && !activity.isDestroyed) render(snapshot)
            }
        }
        controller.addListener(listener)
        render(controller.snapshot())
        try {
            dialog = android.app.AlertDialog.Builder(activity)
                .setView(shell)
                .setNegativeButton("close", null)
                .create()
            dialog?.setOnDismissListener {
                controller.removeListener(listener)
                lease.release()
                onModalVisibilityChanged(false)
            }
            dialog?.show()
            onModalVisibilityChanged(true)
        } catch (error: RuntimeException) {
            controller.removeListener(listener)
            lease.release()
            Toast.makeText(activity, error.message ?: "Could not open agent", Toast.LENGTH_SHORT).show()
        }
    }
}
