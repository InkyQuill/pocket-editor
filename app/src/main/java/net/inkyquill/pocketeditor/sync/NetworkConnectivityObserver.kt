package net.inkyquill.pocketeditor.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

interface ValidatedNetworkSource {
    fun isValidated(): Boolean
    fun register(onValidatedChanged: (Boolean) -> Unit)
}

class NetworkConnectivityObserver internal constructor(source: ValidatedNetworkSource) {
    private val restorationEvents = Channel<Unit>(Channel.CONFLATED)
    private val stateLock = Any()
    private var validated = source.isValidated()

    constructor(context: Context) : this(AndroidValidatedNetworkSource(context.applicationContext))

    val connected: Flow<Unit> = restorationEvents.receiveAsFlow()

    init {
        source.register { current ->
            val restored = synchronized(stateLock) {
                (current && !validated).also { validated = current }
            }
            if (restored) restorationEvents.trySend(Unit)
        }
    }
}

private class AndroidValidatedNetworkSource(context: Context) : ValidatedNetworkSource {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val stateLock = Any()
    private var currentNetwork: Network? = connectivity.activeNetwork

    override fun isValidated(): Boolean = currentNetwork?.let(::isValidated) == true

    override fun register(onValidatedChanged: (Boolean) -> Unit) {
        connectivity.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    synchronized(stateLock) { currentNetwork = network }
                    onValidatedChanged(isValidated(network))
                }

                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    synchronized(stateLock) { currentNetwork = network }
                    onValidatedChanged(capabilities.isValidatedInternet())
                }

                override fun onLost(network: Network) {
                    val activeLost = synchronized(stateLock) {
                        (currentNetwork == network).also { if (it) currentNetwork = null }
                    }
                    if (activeLost) onValidatedChanged(false)
                }
            },
        )
    }

    private fun isValidated(network: Network): Boolean =
        connectivity.getNetworkCapabilities(network)?.isValidatedInternet() == true

    private fun NetworkCapabilities.isValidatedInternet(): Boolean =
        hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}
