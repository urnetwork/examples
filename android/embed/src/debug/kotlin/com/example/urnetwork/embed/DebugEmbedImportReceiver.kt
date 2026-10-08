// Debug builds only (src/debug): imports the token server settings or a scoped client JWT for local
// testing, from adb:
//
//   adb shell am broadcast -n com.example.urnetwork.embed/.DebugEmbedImportReceiver \
//     --es token_server_url http://10.0.2.2:8790 --es demo_session "$DEMO_SESSION"
//   adb shell am broadcast -n com.example.urnetwork.embed/.DebugEmbedImportReceiver \
//     --es client_jwt "$(cat client.jwt)" --ez clear_token_server true
//
// The broadcast result names what was imported, never the session or the token. A release build
// has no import path: your app's own sign-in provides the session.
package com.example.urnetwork.embed

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.io.File

/** Writes token-server.json or client.jwt from the broadcast's extras into the installation state. */
class DebugEmbedImportReceiver : BroadcastReceiver() {
    /** Imports the given extras and reports what was imported, or the problem, as the result. */
    override fun onReceive(context: Context, intent: Intent) {
        val stateDir = File(context.noBackupFilesDir, stateDirName)
        val tokenServerUrl = intent.getStringExtra("token_server_url")
        val demoSession = intent.getStringExtra("demo_session")
        val clientJwt = intent.getStringExtra("client_jwt")
        val clearTokenServer = intent.getBooleanExtra("clear_token_server", false)
        try {
            val imported = mutableListOf<String>()
            if (clearTokenServer) {
                File(stateDir, tokenServerFileName).delete()
                imported += "removed $tokenServerFileName"
            }
            if (tokenServerUrl != null || demoSession != null) {
                // a debug build may use the emulator's 10.0.2.2 for the loopback token server
                val origin = importTokenServerConfig(stateDir, tokenServerUrl.orEmpty(), demoSession.orEmpty(), allowEmulatorHost = true)
                imported += "imported $tokenServerFileName for $origin"
            }
            if (clientJwt != null) {
                val clientId = importClientJwt(stateDir, clientJwt)
                imported += "imported $clientJwtFileName for client $clientId"
            }
            if (imported.isEmpty()) {
                throw EmbedConfigException("pass token_server_url and demo_session, or client_jwt")
            }
            setResult(Activity.RESULT_OK, imported.joinToString("; "), null)
        } catch (e: EmbedConfigException) {
            setResult(Activity.RESULT_CANCELED, e.message, null)
        }
    }
}
