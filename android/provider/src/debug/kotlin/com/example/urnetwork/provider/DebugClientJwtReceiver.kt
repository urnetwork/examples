// Debug builds only (src/debug): imports client.jwt for local testing, from adb:
//
//   adb shell am broadcast -n com.example.urnetwork.provider/.DebugClientJwtReceiver \
//     --es client_jwt "$(cat client.jwt)"
//
// The broadcast result names the imported client, never the token. A release build has no import
// path: an app writes client.jwt from its own sign-in flow with importClientJwt.
package com.example.urnetwork.provider

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.io.File

/** Writes the scoped client JWT from the client_jwt extra into the installation state. */
class DebugClientJwtReceiver : BroadcastReceiver() {
    /** Imports the token and reports the client id, or the problem, as the broadcast result. */
    override fun onReceive(context: Context, intent: Intent) {
        val clientJwt = intent.getStringExtra("client_jwt").orEmpty()
        try {
            val clientId = importClientJwt(File(context.noBackupFilesDir, stateDirName), clientJwt)
            setResult(Activity.RESULT_OK, "imported $clientJwtFileName for client $clientId", null)
        } catch (e: ProviderConfigException) {
            setResult(Activity.RESULT_CANCELED, e.message, null)
        }
    }
}
