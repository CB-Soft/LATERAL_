package com.lateral.privileged

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.RemoteInput

/**
 * Receives the 6-digit pairing code typed into [PairingNotifier]'s `RemoteInput` field, then
 * kicks off [PrivilegedService.activate]. mDNS finds the pairing port automatically — the
 * code came from the notification shade, so the Wireless Debugging pair dialog is still in
 * the foreground and still advertising the `_adb-tls-pairing._tcp` service.
 *
 * Declared in the manifest as a non-exported receiver.
 */
class PairingInputReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != PairingNotifier.ACTION_SUBMIT_CODE) return
        val results = RemoteInput.getResultsFromIntent(intent)
        val raw = results?.getCharSequence(PairingNotifier.KEY_PAIRING_CODE)?.toString().orEmpty()
        val code = raw.filter(Char::isDigit).take(6)
        Log.i(TAG, "pairing code from notification (len=${code.length})")
        if (code.length != 6) return
        val pendingResult = goAsync()
        PrivilegedService.activate(code) { ok ->
            Log.i(TAG, "pair from notification ok=$ok")
            if (ok) {
                PairingNotifier.cancel(context)
            } else {
                PairingNotifier.showPairingPrompt(context)
            }
            pendingResult.finish()
        }
    }

    private companion object {
        const val TAG = "Lateral/Privileged"
    }
}
