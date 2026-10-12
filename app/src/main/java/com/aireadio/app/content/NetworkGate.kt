package com.aireadio.app.content

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.aireadio.app.data.NetworkPolicy

class NetworkGate(context: Context) {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    fun online(): Boolean = manager.getNetworkCapabilities(manager.activeNetwork)
        ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    fun wifi(): Boolean = manager.getNetworkCapabilities(manager.activeNetwork)
        ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    fun mayRefresh(policy: NetworkPolicy) = online() && (policy != NetworkPolicy.WIFI_ONLY || wifi())
    fun mayDownloadModel(policy: NetworkPolicy) = online() && (policy == NetworkPolicy.ALL || wifi())
    fun mayPrefetch(policy: NetworkPolicy) = online() && (policy == NetworkPolicy.ALL || wifi())
}
