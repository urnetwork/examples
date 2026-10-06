// A running provider: the network space manager, the provider device with the installation's
// identity, and the listeners that feed its status (PROVIDER_CONTRACT.md, "App lifecycle"). Sdk
// listeners and callbacks run on sdk threads, so the payout wallet they write is guarded by
// stateLock and the clients-served count is safe for concurrent use. ProviderService creates,
// reads and closes a session on its worker thread.
package com.example.urnetwork.provider

import android.util.Log
import com.bringyour.sdk.ContractDetails
import com.bringyour.sdk.DeviceLocal
import com.bringyour.sdk.DeviceLocalKeyMaterial
import com.bringyour.sdk.NetworkSpaceManager
import com.bringyour.sdk.NetworkSpaceValues
import com.bringyour.sdk.Sdk
import com.bringyour.sdk.SnGetWalletResult
import com.bringyour.sdk.Sub
import java.io.File
import java.io.IOException

private const val logTag = "ProviderSession"

// The device description and spec recorded for this installation's device.
private const val deviceDescription = "Android provider example"
private const val deviceSpec = "urnetwork-examples/android-provider"

// the app version recorded with the device
private const val appVersion = "1"

// The provider extender role's two device settings (PROVIDER_CONTRACT.md, "Extender role"), passed
// explicitly and both on. provideExtenderEnabled is the embedder's hard switch: false means the
// role never runs. defaultProvideExtender is the setting the device uses until the user sets one:
// false turns the default off. Android builds of the sdk carry no extender role, so here neither
// opens a listener; the same call keeps the default on with an sdk build that has the role.
private const val provideExtenderEnabled = true
private const val defaultProvideExtender = true

/**
 * One run of the provider. start creates the device and starts providing publicly; status reads
 * the status fields; close stops providing. The sdk declares provide intent on the device's
 * platform connections by itself while the provide mode is public.
 */
class ProviderSession private constructor(
    private val config: ProviderConfig,
    private val manager: NetworkSpaceManager,
    private val device: DeviceLocal,
) {
    private val clientsServed = ClientsServed(limit = clientsServedLimit)
    private val subs = mutableListOf<Sub>()

    private val stateLock = Any()

    // payoutWalletChecking, payoutWalletUnavailable, payoutWalletNotSet or the mapped coldkey
    private var wallet = payoutWalletChecking

    // "" unless wallet is an address
    private var walletScope = ""

    /** Starts sessions. */
    companion object {
        /**
         * Creates the provider device with the installation's identity and the extender role's
         * settings on, saves a new identity on first run, and starts providing publicly, paused
         * when providePaused. authLogout runs on an sdk thread when the server rejects the client
         * credential.
         */
        fun start(config: ProviderConfig, providePaused: Boolean, authLogout: () -> Unit): ProviderSession {
            val instanceId = Sdk.parseId(config.instanceId)
            val manager = Sdk.newNetworkSpaceManagerNoStorage()
            val space = manager.updateNetworkSpaceValues(
                Sdk.newNetworkSpaceKey("ur.network", "main"),
                NetworkSpaceValues().apply { migrationHostName = "bringyour.com" },
            )
            space.api.setByJwt(config.clientJwt)
            // one call for both runs: on first run there is no identity, the key material is null
            // and the device makes a new identity, saved below
            val device = try {
                Sdk.newDeviceLocalWithProvideExtender(
                    space,
                    config.clientJwt,
                    deviceDescription,
                    deviceSpec,
                    appVersion,
                    instanceId,
                    false,
                    config.identity?.let { keyMaterial(it) },
                    provideExtenderEnabled,
                    defaultProvideExtender,
                )
            } catch (e: Exception) {
                manager.close()
                throw e
            }
            if (config.identity == null) {
                // keep the new identity, so later starts present the same provider
                try {
                    val identity = providerIdentity(config.clientId, device.keyMaterial)
                    if (identity.clientKeySeed.size != 32) {
                        throw IOException("the device has no provider identity to save")
                    }
                    saveProviderIdentity(config.stateDir, identity)
                } catch (e: IOException) {
                    device.close()
                    manager.close()
                    throw IOException("save $identityFileName: ${e.message}", e)
                }
            }

            val session = ProviderSession(config = config, manager = manager, device = device)
            session.subs += device.addJwtRefreshListener { clientJwt -> session.jwtRefreshed(clientJwt) }
            session.subs += device.addAuthLogoutListener { authLogout() }
            session.subs += device.addProviderIngressContractDetailsChangeListener { details ->
                session.contractDetailsChanged(details, receive = true)
            }
            session.subs += device.addProviderEgressContractDetailsChangeListener { details ->
                session.contractDetailsChanged(details, receive = false)
            }
            device.providePaused = providePaused
            device.provideMode = Sdk.ProvideModePublic
            session.syncWallet()
            return session
        }

        /** The key material that recreates the device's identity. */
        private fun keyMaterial(identity: ProviderIdentity): DeviceLocalKeyMaterial {
            val keyMaterial = Sdk.newDeviceLocalKeyMaterial(
                identity.clientKeySeed,
                identity.provideTlsCertificatePem,
                identity.provideTlsPrivateKeyPem,
            )
            keyMaterial.setExtenderKeySeed(identity.extenderKeySeed)
            return keyMaterial
        }

        /** The identity of a running device, for identity.json. */
        private fun providerIdentity(clientId: String, keyMaterial: DeviceLocalKeyMaterial?): ProviderIdentity =
            ProviderIdentity(
                clientId = clientId,
                clientKeySeed = keyMaterial?.clientKeySeed ?: ByteArray(0),
                provideTlsCertificatePem = keyMaterial?.provideTlsCertificatePem ?: ByteArray(0),
                provideTlsPrivateKeyPem = keyMaterial?.provideTlsPrivateKeyPem ?: ByteArray(0),
                extenderKeySeed = keyMaterial?.extenderKeySeed ?: ByteArray(0),
            )
    }

    /** Reads the status from the device getters and the listener state. */
    fun status(): ProviderStatus {
        // never null: "client_limit_exceeded" with the hold's end in RetryTime while the platform
        // holds this client off for its network's client limit
        val clientLimitStatus = device.clientLimitStatus
        // getProviderReady also waits for processed client key registration, which default device
        // settings do not enable, so the connected carrier is the readiness signal here
        val state = providerState(
            device.provideMode,
            clientLimitStatus.status,
            device.providePaused,
            device.provideEnabled,
            device.providerConnected,
        )
        // bytes relayed for clients, in both directions, since the device started
        val dataProvidedByteCount = device.providerPacketStats
            ?.let { it.remoteEgressByteCount + it.remoteIngressByteCount }
            ?: 0
        val served = clientsServed.count()
        return synchronized(stateLock) {
            ProviderStatus(
                state = state,
                clientLimitRetryTime = clientLimitStatus.retryTime,
                clientsServed = served.count,
                clientsServedAtLimit = served.atLimit,
                dataProvidedByteCount = dataProvidedByteCount,
                payoutWallet = wallet,
                payoutWalletScope = walletScope,
            )
        }
    }

    /** Pauses or resumes providing; the provide mode stays public. */
    fun setProvidePaused(providePaused: Boolean) {
        device.providePaused = providePaused
    }

    /**
     * Reads the payout wallet (GET /sn/wallet with the client credential). The app only displays
     * the wallet: the backend maps it, never the app.
     */
    fun syncWallet() {
        device.syncSnWallet { result, err -> walletSynced(result, err) }
    }

    /** Stops providing and releases the device, then the manager. */
    fun close() {
        device.provideMode = Sdk.ProvideModeNone
        for (sub in subs) {
            sub.close()
        }
        device.close()
        manager.close()
    }

    /** Keeps the refreshed credential, so the next start uses a valid token. */
    private fun jwtRefreshed(clientJwt: String) {
        try {
            writePrivateFile(File(config.stateDir, clientJwtFileName), "$clientJwt\n".toByteArray())
        } catch (e: IOException) {
            // never log the token itself
            Log.e(logTag, "could not save the refreshed client credential: ${e.message}")
        }
    }

    /** Counts the peer of one provider contract. */
    private fun contractDetailsChanged(details: ContractDetails?, receive: Boolean) {
        if (details == null) {
            return
        }
        val path = details.contractTransferPath
        clientsServed.add(
            contractPeerKey(
                contractId = details.contractId?.toString(),
                sourceId = path?.sourceId?.toString(),
                destinationId = path?.destinationId?.toString(),
                streamId = path?.streamId?.toString(),
                receive = receive,
            ),
        )
    }

    /** Records the wallet read. A failure keeps the last known wallet. */
    private fun walletSynced(result: SnGetWalletResult?, err: Exception?) {
        if (err != null || result == null || result.error != null) {
            synchronized(stateLock) {
                if (wallet == payoutWalletChecking) {
                    wallet = payoutWalletUnavailable
                }
            }
            return
        }
        // the sdk caches the effective wallet before the callback: this client's own consent, else
        // the network consent, else (with hotkey delegations) the network's hotkey entry, else a
        // non-consent wallet
        val snWallet = device.snWallet
        synchronized(stateLock) {
            val coldkeySs58 = snWallet?.coldkeySs58.orEmpty()
            if (coldkeySs58 == "") {
                wallet = payoutWalletNotSet
                walletScope = ""
                return
            }
            wallet = coldkeySs58
            walletScope = payoutWalletScope(snWallet?.consentScope.orEmpty(), snWallet?.clientId.orEmpty(), config.clientId)
        }
    }
}
