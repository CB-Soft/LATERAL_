package com.lateral.beast

import android.content.Context

enum class LauncherSort(val title: String) {
    ALPHABETICAL("a-z"),
    RECENTLY_USED("recent"),
    MOST_USED("most used"),
}

/** Small, durable launcher state; Android supplies recency, while LATERAL tracks launches. */
class LauncherPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("beast_launcher", Context.MODE_PRIVATE)

    var sort: LauncherSort
        get() = runCatching {
            LauncherSort.valueOf(prefs.getString(KEY_SORT, null) ?: "")
        }.getOrDefault(LauncherSort.RECENTLY_USED)
        set(value) { prefs.edit().putString(KEY_SORT, value.name).apply() }

    fun recordUse(packageName: String) {
        prefs.edit()
            .putLong("last.$packageName", System.currentTimeMillis())
            .putLong("count.$packageName", prefs.getLong("count.$packageName", 0L) + 1L)
            .apply()
    }

    fun lastUsed(packageName: String): Long = prefs.getLong("last.$packageName", 0L)
    fun useCount(packageName: String): Long = prefs.getLong("count.$packageName", 0L)

    private companion object { const val KEY_SORT = "sort" }
}
