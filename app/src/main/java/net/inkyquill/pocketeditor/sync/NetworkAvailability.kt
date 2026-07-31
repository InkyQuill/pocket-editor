package net.inkyquill.pocketeditor.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

fun interface NetworkAvailability {
    fun hasValidatedInternet(): Boolean
}

class AndroidNetworkAvailability(context: Context) : NetworkAvailability {
    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)

    override fun hasValidatedInternet(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
