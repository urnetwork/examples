// The provider app's one screen (PROVIDER_CONTRACT.md, "Android"): the consent disclaimer next to
// Start, the four status fields and Stop. While visible it binds to ProviderService and shows the
// service's latest status every second. Starting is an explicit user action; the service then keeps
// providing in the foreground until Stop, here or in its notification.
package com.example.urnetwork.provider

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.TextView
import com.bringyour.sdk.Sdk

// how often the screen shows the latest status
private const val refreshIntervalMillis = 1000L

private const val notificationPermissionRequestCode = 1

/** The screen that starts and stops providing and shows the provider status. */
class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())

    // the bound service, while bound
    private var service: ProviderService? = null

    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var statusValue: TextView
    private lateinit var clientsServedValue: TextView
    private lateinit var dataProvidedValue: TextView
    private lateinit var payoutWalletValue: TextView
    private lateinit var problemText: TextView

    private val connection = object : ServiceConnection {
        /** Shows the bound service's status. */
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as ProviderService.LocalBinder).service
            render()
        }

        /** Shows the stopped status until the service is bound again. */
        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            render()
        }
    }

    private val refresh = object : Runnable {
        /** Shows the latest status, then again after the refresh interval. */
        override fun run() {
            render()
            handler.postDelayed(this, refreshIntervalMillis)
        }
    }

    /** Shows the disclaimer and wires Start and Stop. */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        startButton = findViewById(R.id.start)
        stopButton = findViewById(R.id.stop)
        statusValue = findViewById(R.id.status_value)
        clientsServedValue = findViewById(R.id.clients_served_value)
        dataProvidedValue = findViewById(R.id.data_provided_value)
        payoutWalletValue = findViewById(R.id.payout_wallet_value)
        problemText = findViewById(R.id.problem)
        // the contract's exact text, which the unit tests check
        findViewById<TextView>(R.id.disclaimer).text = consentDisclaimer
        findViewById<TextView>(R.id.sdk_version).text = getString(R.string.sdk_version, Sdk.getVersion())
        startButton.setOnClickListener { requestStart() }
        stopButton.setOnClickListener {
            startService(Intent(this, ProviderService::class.java).setAction(ProviderService.actionStop))
        }
    }

    /** Binds to the service and starts showing its status. */
    override fun onStart() {
        super.onStart()
        bindService(Intent(this, ProviderService::class.java), connection, BIND_AUTO_CREATE)
        handler.post(refresh)
    }

    /** Stops showing the status; providing continues in the service. */
    override fun onStop() {
        handler.removeCallbacks(refresh)
        unbindService(connection)
        service = null
        super.onStop()
    }

    /** Starts providing once the notification permission was asked for. */
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == notificationPermissionRequestCode) {
            // providing does not need the permission: without it the notification is hidden
            startProviding()
        }
    }

    /**
     * Asks for the notification permission on Android 13 and later, then starts providing. An app
     * built from this example collects the user's consent before this point.
     */
    private fun requestStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), notificationPermissionRequestCode)
            return
        }
        startProviding()
    }

    /** Starts the foreground service that provides. */
    private fun startProviding() {
        startForegroundService(Intent(this, ProviderService::class.java).setAction(ProviderService.actionStart))
    }

    /** Shows the service's latest status, or stopped while no service is bound. */
    private fun render() {
        val service = service
        val status = service?.status ?: ProviderStatus(state = providerStateStopped)
        statusValue.text = status.statusText()
        clientsServedValue.text = status.clientsServedText()
        dataProvidedValue.text = status.dataProvidedText()
        payoutWalletValue.text = status.payoutWalletText()
        val problem = service?.problem.orEmpty()
        problemText.text = problem
        problemText.visibility = if (problem == "") View.GONE else View.VISIBLE
        val running = service?.running == true
        startButton.isEnabled = !running
        stopButton.isEnabled = running
    }
}
