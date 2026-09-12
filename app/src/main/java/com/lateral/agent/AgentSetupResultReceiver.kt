package com.lateral.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Receives only the one-shot explicit PendingIntent supplied to Termux. */
class AgentSetupResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val result = intent.getBundleExtra("result") ?: return
        val internalError = result.getInt("err", -1)
        ManagedAgentController.get(context).setupResult(
            result.getInt("exitCode", 1),
            if (internalError == -1) "" else result.getString("errmsg").orEmpty().take(300),
            listOf(result.getString("stdout").orEmpty(), result.getString("stderr").orEmpty()).filter(String::isNotBlank).joinToString("\n"),
        )
    }
}
