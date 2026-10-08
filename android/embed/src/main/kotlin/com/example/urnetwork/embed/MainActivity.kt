// The embed app's one screen (EMBED_CONTRACT.md, "Android"): Start, the status fields with the
// client and installation IDs, Stop, the sdk's licenses and the next step. While visible it shows the
// controller's latest snapshot every second. Starting is an explicit user action; the device then
// runs in the app-scoped controller until Stop or a sign-out.
package com.example.urnetwork.embed

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.TextView
import com.bringyour.sdk.Sdk

// how often the screen shows the latest status
private const val refreshIntervalMillis = 1000L

// the app kind whose licenses the sdk returns
private const val licensesAppKind = "android"

/** The screen that starts and stops the embedded device and shows its status. */
class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var controller: EmbedController

    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var statusValue: TextView
    private lateinit var dataMonthValue: TextView
    private lateinit var dataTotalValue: TextView
    private lateinit var clientIdValue: TextView
    private lateinit var installationIdValue: TextView
    private lateinit var problemText: TextView

    private val refresh = object : Runnable {
        /** Shows the latest status, then again after the refresh interval. */
        override fun run() {
            render()
            handler.postDelayed(this, refreshIntervalMillis)
        }
    }

    /** Wires Start, Stop and Licenses. */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        controller = (application as EmbedApplication).controller
        startButton = findViewById(R.id.start)
        stopButton = findViewById(R.id.stop)
        statusValue = findViewById(R.id.status_value)
        dataMonthValue = findViewById(R.id.data_month_value)
        dataTotalValue = findViewById(R.id.data_total_value)
        clientIdValue = findViewById(R.id.client_id_value)
        installationIdValue = findViewById(R.id.installation_id_value)
        problemText = findViewById(R.id.problem)
        findViewById<TextView>(R.id.sdk_version).text = getString(R.string.sdk_version, Sdk.getVersion())
        startButton.setOnClickListener {
            controller.start()
            render()
        }
        stopButton.setOnClickListener {
            controller.stop()
            render()
        }
        findViewById<Button>(R.id.licenses).setOnClickListener { showLicenses() }
    }

    /** Starts showing the status. */
    override fun onStart() {
        super.onStart()
        handler.post(refresh)
    }

    /** Stops showing the status; the device keeps running in the controller. */
    override fun onStop() {
        handler.removeCallbacks(refresh)
        super.onStop()
    }

    /** Shows the controller's latest snapshot. */
    private fun render() {
        val snapshot = controller.snapshot()
        statusValue.text = snapshot.fields.status
        dataMonthValue.text = snapshot.fields.dataThisMonth
        dataTotalValue.text = snapshot.fields.dataTotal
        clientIdValue.text = snapshot.clientId.ifEmpty { getString(R.string.not_known) }
        installationIdValue.text = snapshot.instanceId.ifEmpty { getString(R.string.not_known) }
        problemText.text = snapshot.problem
        problemText.visibility = if (snapshot.problem == "") View.GONE else View.VISIBLE
        startButton.isEnabled = !snapshot.running
        stopButton.isEnabled = snapshot.running
    }

    /** Shows the sdk's licenses and data attributions, read off the main thread. */
    private fun showLicenses() {
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.licenses_title)
            .setMessage(R.string.licenses_loading)
            .setPositiveButton(R.string.close, null)
            .show()
        Thread {
            val text = licensesText()
            runOnUiThread {
                if (dialog.isShowing) {
                    dialog.setMessage(text)
                }
            }
        }.start()
    }
}

/**
 * The sdk's licenses and data attributions for Android apps (GetLicenses, no network), one per
 * line: name, version and SPDX identifier. Publish them with your app.
 */
private fun licensesText(): String {
    val licenses = Sdk.getLicenses(licensesAppKind) ?: return ""
    return (0L until licenses.len()).joinToString("\n") { i ->
        val license = licenses.get(i)
        val title = listOf(license.name.orEmpty(), license.version.orEmpty()).filter { it != "" }.joinToString(" ")
        val spdx = license.spdx.orEmpty()
        if (spdx == "") title else "$title — $spdx"
    }
}
