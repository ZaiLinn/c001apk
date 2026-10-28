package com.zailinn.c001apk.ui.common

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext

fun isValidatedWifi(context: Context): Boolean {
    val manager = context.getSystemService(ConnectivityManager::class.java)
    return manager?.getNetworkCapabilities(manager.activeNetwork)?.let {
        it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    } == true
}

@Composable
fun rememberWifi(): Boolean {
    val context = LocalContext.current
    val manager = context.getSystemService(ConnectivityManager::class.java)
    var wifi by remember(manager) { mutableStateOf(isValidatedWifi(context)) }
    DisposableEffect(manager) {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) { wifi = isValidatedWifi(context) }
            override fun onLost(network: Network) { wifi = isValidatedWifi(context) }
        }
        manager?.registerDefaultNetworkCallback(callback)
        onDispose { runCatching { manager?.unregisterNetworkCallback(callback) } }
    }
    return wifi
}
