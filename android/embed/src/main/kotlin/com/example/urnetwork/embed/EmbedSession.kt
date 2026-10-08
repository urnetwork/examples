// A running embedded device (EMBED_CONTRACT.md, "App lifecycle"): the network space manager, the
// local device for this installation's client, and the listeners that keep the credential and the
// caps current. The device carries only the app's own traffic; it never provides and needs no
// VpnService. Sdk listeners run on sdk threads, so they only hand work to the controller.
// EmbedController creates, reads and closes a session on its worker thread.
package com.example.urnetwork.embed

import com.bringyour.sdk.ConnectLocation
import com.bringyour.sdk.ConnectLocationId
import com.bringyour.sdk.DeviceLocal
import com.bringyour.sdk.NetworkSpaceManager
import com.bringyour.sdk.NetworkSpaceValues
import com.bringyour.sdk.Sdk
import com.bringyour.sdk.Sub

// The device description and spec recorded for this installation's device.
private const val deviceDescription = "Android embed example"
private const val deviceSpec = "urnetwork-examples/android-embed"

// the app version recorded with the device
private const val appVersion = "1"

/** What the status rules read from the device each second. */
data class DeviceReading(
    // "" or Sdk.ClientLimitStatusExceeded
    val clientLimitStatus: String = "",
    // unix milliseconds; 0 when there is no hold
    val clientLimitRetryTime: Long = 0,
    // ProviderStateAdded of the window status; 0 before the window exists
    val providersAdded: Long = 0,
)

/**
 * One run of the embedded device. start creates the device for the installation's client and
 * points it at the best available location; read reads what the status shows; close releases it.
 */
class EmbedSession private constructor(
    private val manager: NetworkSpaceManager,
    private val device: DeviceLocal,
) {
    private val subs = mutableListOf<Sub>()

    /** Starts sessions. */
    companion object {
        /**
         * Creates the manager and the ur.network/main space with the client JWT, then the local
         * device with the installation's instance id, adds the listeners and sets the connect
         * location to best available: the destination of the app's own traffic. The provide mode
         * stays at its default, so the device does not provide. The listeners run on sdk threads:
         * jwtRefreshed with the refreshed token, authLogout when the server rejects the client
         * credential, contractStatusChanged when a contract opens or closes.
         */
        fun start(
            clientJwt: String,
            instanceId: String,
            jwtRefreshed: (String) -> Unit,
            authLogout: () -> Unit,
            contractStatusChanged: () -> Unit,
        ): EmbedSession {
            val manager = Sdk.newNetworkSpaceManagerNoStorage()
            val device = try {
                val space = manager.updateNetworkSpaceValues(
                    Sdk.newNetworkSpaceKey("ur.network", "main"),
                    NetworkSpaceValues().apply { migrationHostName = "bringyour.com" },
                )
                space.api.setByJwt(clientJwt)
                Sdk.newDeviceLocalWithDefaults(
                    space,
                    clientJwt,
                    deviceDescription,
                    deviceSpec,
                    appVersion,
                    Sdk.parseId(instanceId),
                    false,
                )
            } catch (e: Exception) {
                manager.close()
                throw e
            }
            val session = EmbedSession(manager = manager, device = device)
            try {
                session.subs += device.addJwtRefreshListener { refreshedJwt -> jwtRefreshed(refreshedJwt) }
                session.subs += device.addAuthLogoutListener { authLogout() }
                session.subs += device.addContractStatusChangeListener { contractStatusChanged() }
                device.setConnectLocation(
                    ConnectLocation().apply {
                        connectLocationId = ConnectLocationId().apply { bestAvailable = true }
                    },
                )
            } catch (e: Exception) {
                session.close()
                throw e
            }
            return session
        }
    }

    /** Reads the client limit status and the providers added to the device's window. */
    fun read(): DeviceReading {
        // never null on a local device: "client_limit_exceeded" with the hold's end in RetryTime
        // while the platform holds this client off for its network's client limit
        val clientLimitStatus = device.clientLimitStatus
        // null before the window exists
        val windowStatus = device.windowStatus
        return DeviceReading(
            clientLimitStatus = clientLimitStatus?.status.orEmpty(),
            clientLimitRetryTime = clientLimitStatus?.retryTime ?: 0,
            providersAdded = windowStatus?.providerStateAdded ?: 0,
        )
    }

    /** Closes the subscriptions, then the device, then the manager. */
    fun close() {
        for (sub in subs) {
            sub.close()
        }
        subs.clear()
        device.close()
        manager.close()
    }
}
