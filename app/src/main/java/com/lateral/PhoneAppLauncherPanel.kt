package com.lateral

import android.app.Activity
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.lateral.beast.BeastTask
import com.lateral.beast.AndroidTaskSynchronizer
import com.lateral.beast.LauncherPreferences
import com.lateral.beast.LauncherSort
import com.lateral.beast.WorkspaceState
import com.lateral.privileged.PrivilegedService

/** Phone-display companion to Beast's launcher, backed by the same durable sort state. */
object PhoneAppLauncherPanel {
    fun show(
        activity: Activity,
        onLaunch: (ResolveInfo) -> Unit,
        onOpen: (BeastTask) -> Unit,
        onModalVisibilityChanged: (Boolean) -> Unit = {},
    ) {
        if (activity.isFinishing || activity.isDestroyed) {
            onModalVisibilityChanged(false)
            return
        }
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val preferences = LauncherPreferences(activity.applicationContext)
        val packageManager = activity.packageManager
        val apps = packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0,
        ).filter { it.activityInfo.packageName != activity.packageName }
        val recentRank = PrivilegedService.recentTasks().map { it.packageName }.distinct()
            .withIndex().associate { it.value to it.index }

        var dialog: android.app.AlertDialog? = null
        val lease = TransientPanelCoordinator.claim(
            activity.display?.displayId ?: android.view.Display.DEFAULT_DISPLAY,
            TransientPanelCoordinator.Kind.APPS,
        ) { dialog?.dismiss() }

        val shell = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(8))
            setBackgroundColor(Color.rgb(9, 10, 11))
        }
        val query = EditText(activity).apply {
            hint = "find app"
            isSingleLine = true
            typeface = Typeface.MONOSPACE
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(105, 119, 119))
        }
        val sortBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val results = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(activity).apply { addView(results) }

        fun label(info: ResolveInfo) = info.loadLabel(packageManager).toString()
        fun ordered(): List<ResolveInfo> = when (preferences.sort) {
            LauncherSort.ALPHABETICAL -> apps.sortedBy { label(it).lowercase() }
            LauncherSort.RECENTLY_USED -> apps.sortedWith(
                compareBy<ResolveInfo> { recentRank[it.activityInfo.packageName] ?: Int.MAX_VALUE }
                    .thenByDescending { preferences.lastUsed(it.activityInfo.packageName) }
                    .thenBy { label(it).lowercase() },
            )
            LauncherSort.MOST_USED -> apps.sortedWith(
                compareByDescending<ResolveInfo> { preferences.useCount(it.activityInfo.packageName) }
                    .thenByDescending { preferences.lastUsed(it.activityInfo.packageName) }
                    .thenBy { label(it).lowercase() },
            )
        }

        fun dismissAnd(action: () -> Unit) {
            dialog?.dismiss()
            action()
        }

        fun populate(filter: String) {
            results.removeAllViews()
            ordered().asSequence()
                .filter { label(it).contains(filter, ignoreCase = true) }
                .forEach { info ->
                    val packageName = info.activityInfo.packageName
                    val openTasks = WorkspaceState.tasks.filter { it.packageName == packageName }
                        .sortedBy(BeastTask::overviewRank)
                    val titles = openTasks.map { it.instanceTitle.trim() }
                    val useTitles = openTasks.size > 1 && titles.all(String::isNotBlank) &&
                        titles.distinctBy(String::lowercase).size == openTasks.size
                    val row = LinearLayout(activity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(4), 0, dp(4), 0)
                    }
                    row.addView(TextView(activity).apply {
                        text = label(info).lowercase()
                        textSize = 15f
                        typeface = Typeface.MONOSPACE
                        gravity = Gravity.CENTER_VERTICAL
                        setTextColor(Color.rgb(215, 224, 222))
                        setOnClickListener {
                            preferences.recordUse(packageName)
                            dismissAnd { onLaunch(info) }
                        }
                    }, LinearLayout.LayoutParams(0, dp(46), 1f))
                    if (openTasks.isNotEmpty()) {
                        val instances = LinearLayout(activity).apply {
                            orientation = LinearLayout.VERTICAL
                            gravity = Gravity.END
                        }
                        openTasks.forEachIndexed { index, task ->
                            val designation = when {
                                openTasks.size == 1 -> ""
                                useTitles -> " · ${task.instanceTitle.trim().take(28)}"
                                else -> " #${index + 1}"
                            }
                            instances.addView(TextView(activity).apply {
                                text = "● open$designation"
                                textSize = 11f
                                typeface = Typeface.MONOSPACE
                                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                                setTextColor(InputSettings.accentColor)
                                setPadding(dp(10), 0, 0, 0)
                                setOnClickListener {
                                    preferences.recordUse(packageName)
                                    dismissAnd { onOpen(task) }
                                }
                            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(30)))
                        }
                        row.addView(instances)
                    }
                    results.addView(row, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        maxOf(dp(46), dp(30) * openTasks.size),
                    ))
                }
        }

        fun rebuildSortBar() {
            sortBar.removeAllViews()
            LauncherSort.entries.forEach { option ->
                sortBar.addView(TextView(activity).apply {
                    text = option.title
                    gravity = Gravity.CENTER
                    textSize = 12f
                    typeface = Typeface.MONOSPACE
                    setTextColor(
                        if (preferences.sort == option) InputSettings.accentColor
                        else Color.rgb(105, 119, 119),
                    )
                    setOnClickListener {
                        preferences.sort = option
                        rebuildSortBar()
                        populate(query.text.toString())
                    }
                }, LinearLayout.LayoutParams(0, dp(38), 1f))
            }
        }

        query.addTextChangedListener(SimpleTextWatcher(::populate))
        rebuildSortBar()
        populate("")
        shell.addView(query, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)))
        shell.addView(sortBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)))
        shell.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val stateListener: () -> Unit = {
            activity.runOnUiThread {
                if (!activity.isFinishing && !activity.isDestroyed) {
                    populate(query.text.toString())
                }
            }
        }
        WorkspaceState.addListener(stateListener)
        AndroidTaskSynchronizer.requestImmediate()
        try {
            dialog = android.app.AlertDialog.Builder(activity)
                .setTitle("LATERAL_ / apps")
                .setView(shell)
                .setNegativeButton("Close", null)
                .show()
            onModalVisibilityChanged(true)
            dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, dp(720))
            dialog?.window?.setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
            )
            query.requestFocus()
            query.post {
                (activity.getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(query, InputMethodManager.SHOW_IMPLICIT)
            }
            dialog?.setOnDismissListener {
                WorkspaceState.removeListener(stateListener)
                lease.release()
                onModalVisibilityChanged(false)
            }
        } catch (error: RuntimeException) {
            WorkspaceState.removeListener(stateListener)
            lease.release()
            onModalVisibilityChanged(false)
            Toast.makeText(
                activity, error.message ?: "Could not open apps", Toast.LENGTH_SHORT,
            ).show()
        }
    }
}
