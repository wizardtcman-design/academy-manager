package com.example.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.example.data.repository.AcademyRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class NetworkSyncManager(
    private val context: Context,
    private val academyRepository: AcademyRepository
) {
    private val scope = CoroutineScope(Dispatchers.IO)
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _isOnline = MutableStateFlow(checkInitialConnectivity())
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    private var syncJob: Job? = null
    private var isRegistered = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            super.onAvailable(network)
            Log.d("NetworkSyncManager", "Network is available - triggering sync")
            _isOnline.value = true
            triggerDebouncedSync()
        }

        override fun onLost(network: Network) {
            super.onLost(network)
            Log.d("NetworkSyncManager", "Network connection lost - operating in offline mode")
            _isOnline.value = checkInitialConnectivity()
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            super.onCapabilitiesChanged(network, networkCapabilities)
            val hasInternet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            _isOnline.value = hasInternet
            if (hasInternet) {
                triggerDebouncedSync()
            }
        }
    }

    fun startListening() {
        if (isRegistered) return
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager.registerNetworkCallback(request, networkCallback)
            isRegistered = true
        } catch (e: Exception) {
            Log.e("NetworkSyncManager", "Failed to register network callback", e)
        }
    }

    fun stopListening() {
        if (!isRegistered) return
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
            isRegistered = false
        } catch (e: Exception) {
            Log.e("NetworkSyncManager", "Failed to unregister network callback", e)
        }
    }

    private fun checkInitialConnectivity(): Boolean {
        return try {
            val activeNetwork = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            false
        }
    }

    fun triggerDebouncedSync() {
        syncJob?.cancel()
        syncJob = scope.launch {
            Log.d("NetworkSyncManager", "[TRACE 3] NetworkSyncManager triggered debounced sync")
            delay(2000) // Wait 2 seconds for connection to stabilize
            try {
                if (academyRepository.isFirebaseReady()) {
                    Log.d("NetworkSyncManager", "[TRACE 4] performCloudSync() started by NetworkSyncManager")
                    academyRepository.performCloudSync()
                } else {
                    Log.d("NetworkSyncManager", "Auto-sync skipped: Firebase Auth session not ready")
                }
            } catch (e: Exception) {
                Log.w("NetworkSyncManager", "[TRACE 4 ERROR] Background auto-sync failed gracefully: ${e.message}")
            }
            // Keep the Cloudflare bot server in sync as well (works while the app is closed)
            try {
                com.example.service.messaging.BotCloudBridge(context).pushAll()
            } catch (e: Exception) {
                Log.w("NetworkSyncManager", "Bot server push skipped: ${e.message}")
            }
        }
    }
}
