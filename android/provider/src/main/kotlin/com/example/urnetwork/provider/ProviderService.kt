// The foreground service that owns the provider device while the user has started providing
// (PROVIDER_CONTRACT.md, "Android"). The activity starts it with actionStart and binds to it for the
// status; the activity and the notification stop it with actionStop. It never starts by itself: after
// the system ends the process, providing starts again only from the app.
//
// Start and stop decisions run on the main thread. The sdk work (opening and closing the session,
// the status reads every second, the wallet reads every 10 minutes and pausing on metered networks)
// runs on one worker thread, in order, so a stop always closes the session of the start before it.
// The activity reads the latest status and problem from any thread.
package com.example.urnetwork.provider

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.bringyour.sdk.Sdk
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

private const val logTag = "ProviderService"

// How often the status is read, and the longest gap between notification updates.
private const val statusPollIntervalMillis = 1000L
private const val statusRepeatIntervalMillis = 60L * 1000

// How often the payout wallet is read again. The wallet is fixed; a reread shows a mapping that the
// backend completes while the provider runs.
private const val walletSyncIntervalMillis = 10L * 60 * 1000

// Providing pauses on metered networks (mobile data, metered Wi-Fi) by default; true keeps
// providing on them.
private const val provideOnMeteredNetworks = false

private const val notificationChannelId = "provider"
private const val notificationId = 1

/** Runs the provider in the foreground while the user has started providing. */
class ProviderService : Service() {
    /** The activity's handle on the service. */
    inner class LocalBinder : Binder() {
        /** The bound service. */
        val service: ProviderService
            get() = this@ProviderService
    }

    /** The actions that start and stop providing. */
    companion object {
        /** Starts providing: the activity sends it with startForegroundService. */
        const val actionStart = "com.example.urnetwork.provider.action.START"

        /** Stops providing: the activity and the notification send it. */
        const val actionStop = "com.example.urnetwork.provider.action.STOP"

        // the message shown when the server no longer accepts the client credential
        private const val credentialRejected =
            "the server rejected the client credential; issue a new scoped client JWT from your backend"
    }

    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var worker: ScheduledExecutorService
    private lateinit var notificationManager: NotificationManager
    private lateinit var connectivityManager: ConnectivityManager

    // main thread only
    private var runGeneration = 0
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // worker thread only
    private var session: ProviderSession? = null
    private var statusTask: ScheduledFuture<*>? = null
    private var lastNotificationKey = ""
    private var lastNotificationTime = 0L
    private var nextWalletSyncTime = 0L

    // whether the default network is metered, from the network callback
    @Volatile
    private var metered = false

    /** The latest status snapshot; stopped until providing starts. */
    @Volatile
    var status = ProviderStatus(state = providerStateStopped)
        private set

    /** The last configuration or credential problem, for the activity to show; "" when none. */
    @Volatile
    var problem = ""
        private set

    /** Whether the user started providing and has not stopped it. */
    @Volatile
    var running = false
        private set

    /** Creates the worker thread and the notification channel. */
    override fun onCreate() {
        super.onCreate()
        worker = Executors.newSingleThreadScheduledExecutor()
        notificationManager = getSystemService(NotificationManager::class.java)
        connectivityManager = getSystemService(ConnectivityManager::class.java)
        notificationManager.createNotificationChannel(
            NotificationChannel(
                notificationChannelId,
                getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    /** Hands the activity the service. */
    override fun onBind(intent: Intent): IBinder = binder

    /** Starts or stops providing. The service never restarts by itself. */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            actionStart -> startProviding()
            actionStop -> stopProviding("")
            else -> if (!running) {
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    /** Closes a session that is still open and ends the worker thread. */
    override fun onDestroy() {
        unregisterNetworkCallback()
        running = false
        onWorker { closeSession() }
        worker.shutdown()
        super.onDestroy()
    }

    /**
     * Enters the foreground at once, as startForegroundService requires, and opens the session on
     * the worker thread unless providing already runs.
     */
    private fun startProviding() {
        if (running) {
            // a repeated start keeps the current status in the notification
            enterForeground(status)
            return
        }
        enterForeground(ProviderStatus(state = providerStateStarting))
        running = true
        problem = ""
        runGeneration += 1
        val generation = runGeneration
        registerNetworkCallback()
        onWorker { openSession(generation) }
    }

    /**
     * Stops providing: the worker closes the session, then the service leaves the foreground and
     * stops unless the user started providing again meanwhile. A non-empty problem is shown by the
     * activity.
     */
    private fun stopProviding(problemText: String) {
        if (problemText != "") {
            problem = problemText
        }
        if (running) {
            running = false
            unregisterNetworkCallback()
        }
        onWorker {
            closeSession()
            mainHandler.post {
                if (!running) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    /** Stops the run of generation with a problem, unless that run already ended. */
    private fun stopRun(generation: Int, problemText: String) {
        mainHandler.post {
            if (running && generation == runGeneration) {
                stopProviding(problemText)
            }
        }
    }

    /** Runs task on the worker thread; ignored once the service is destroyed. */
    private fun onWorker(task: () -> Unit) {
        try {
            worker.execute(task)
        } catch (e: RejectedExecutionException) {
            // the worker ended with the service
        }
    }

    /** Loads the installation state and starts the provider; worker thread. */
    private fun openSession(generation: Int) {
        status = ProviderStatus(state = providerStateStarting)
        val stateDir = File(noBackupFilesDir, stateDirName)
        val config = try {
            ensureStateDir(stateDir)
            loadProviderConfig(stateDir.path)
        } catch (e: ProviderConfigException) {
            stopRun(generation, e.message.orEmpty())
            return
        }
        try {
            // the sdk keeps its bounded log files there (16 MiB files, the newest four kept at
            // each start) instead of a directory this app cannot write
            Sdk.setLogDir(ensureLogDir(stateDir).path)
        } catch (e: Exception) {
            stopRun(generation, "could not set the sdk log directory: ${e.message}")
            return
        }
        val session = try {
            ProviderSession.start(
                config = config,
                providePaused = metered && !provideOnMeteredNetworks,
                authLogout = { stopRun(generation, credentialRejected) },
            )
        } catch (e: Exception) {
            stopRun(generation, "could not start the provider: ${e.message}")
            return
        }
        this.session = session
        lastNotificationKey = ""
        nextWalletSyncTime = SystemClock.elapsedRealtime() + walletSyncIntervalMillis
        statusTask = worker.scheduleWithFixedDelay(
            { updateStatus() },
            0,
            statusPollIntervalMillis,
            TimeUnit.MILLISECONDS,
        )
    }

    /** Stops providing and releases the device; worker thread. */
    private fun closeSession() {
        statusTask?.cancel(false)
        statusTask = null
        session?.close()
        session = null
        status = status.copy(state = providerStateStopped)
    }

    /**
     * Reads the status, updates the notification when the status text, the clients-served count or
     * the payout wallet changes and at least once a minute, and rereads the wallet every 10 minutes;
     * worker thread.
     */
    private fun updateStatus() {
        val session = session ?: return
        try {
            val status = session.status()
            this.status = status
            val now = SystemClock.elapsedRealtime()
            val key = status.key()
            if (key != lastNotificationKey || statusRepeatIntervalMillis <= now - lastNotificationTime) {
                notificationManager.notify(notificationId, notification(status))
                lastNotificationKey = key
                lastNotificationTime = now
            }
            if (nextWalletSyncTime <= now) {
                session.syncWallet()
                nextWalletSyncTime = now + walletSyncIntervalMillis
            }
        } catch (e: Exception) {
            // a failed read must not end the scheduled reads
            Log.e(logTag, "could not read the provider status: ${e.message}")
        }
    }

    /** Pauses providing on a metered network, unless provideOnMeteredNetworks. */
    private fun registerNetworkCallback() {
        metered = connectivityManager.isActiveNetworkMetered
        val callback = object : ConnectivityManager.NetworkCallback() {
            /** Follows the metered state of the default network. */
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                val unmetered = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED))
                metered = !unmetered
                onWorker { session?.setProvidePaused(metered && !provideOnMeteredNetworks) }
            }
        }
        connectivityManager.registerDefaultNetworkCallback(callback)
        networkCallback = callback
    }

    /** Stops following the default network. */
    private fun unregisterNetworkCallback() {
        networkCallback?.let { connectivityManager.unregisterNetworkCallback(it) }
        networkCallback = null
    }

    /** Shows the provider notification as the service's foreground notification. */
    private fun enterForeground(status: ProviderStatus) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(notificationId, notification(status), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(notificationId, notification(status))
        }
    }

    /** The ongoing notification: the status, the status line, and Stop. */
    private fun notification(status: ProviderStatus): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            0,
            Intent(this, ProviderService::class.java).setAction(actionStop),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, notificationChannelId)
            .setSmallIcon(R.drawable.ic_provider)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(status.statusText())
            .setStyle(Notification.BigTextStyle().bigText(status.line()))
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_provider),
                    getString(R.string.stop),
                    stop,
                ).build(),
            )
            .build()
    }
}
