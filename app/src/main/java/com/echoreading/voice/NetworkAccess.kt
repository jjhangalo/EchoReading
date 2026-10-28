package com.echoreading.voice

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

object NetworkAccess {
    fun isAvailable(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = manager.activeNetwork ?: return false
        return manager.getNetworkCapabilities(network)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    fun isConnectionFailure(error: Throwable): Boolean = generateSequence(error) { it.cause }
        .any { it is UnknownHostException || it is ConnectException || it is NoRouteToHostException ||
            it is SocketTimeoutException || it is SocketException }
}
