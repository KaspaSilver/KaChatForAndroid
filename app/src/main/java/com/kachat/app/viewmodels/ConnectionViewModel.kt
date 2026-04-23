package com.kachat.app.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kachat.app.repository.AppSettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class ConnectionStatus {
    CONNECTED, WEAK, DISCONNECTED
}

data class NodeInfo(
    val ip: String,
    val type: String, // Seed, Manual
    val latency: String,
    val distance: String,
    val country: String,
    val daaScore: String,
    val status: String, // Active, Quarantined, Suspect, Candidate
    val color: Long // Hex color for status
)

@HiltViewModel
class ConnectionViewModel @Inject constructor(
    private val settings: AppSettingsRepository,
    private val nodePoolManager: com.kachat.app.services.NodePoolManager
) : ViewModel() {

    private val _status = MutableStateFlow(ConnectionStatus.CONNECTED)
    val status: StateFlow<ConnectionStatus> = _status

    val network: StateFlow<String> = settings.network.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), "Mainnet")
    val indexerUrl: StateFlow<String> = settings.indexerUrl.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), "")
    val pushIndexerUrl: StateFlow<String> = MutableStateFlow("https://indexer.kasia.wtf") 
    val knsApiUrl: StateFlow<String> = settings.knsApiUrl.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), "")
    val kaspaRestApiUrl: StateFlow<String> = settings.kaspaRestUrl.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), "")

    val activeNodes: StateFlow<List<NodeInfo>> = nodePoolManager.activeNodes
    val allNodes: StateFlow<List<NodeInfo>> = nodePoolManager.allNodes

    private val _discoverNewPeers = MutableStateFlow(true)
    val discoverNewPeers: StateFlow<Boolean> = _discoverNewPeers

    fun setNetwork(value: String) { viewModelScope.launch { settings.setNetwork(value) } }
    fun setIndexerUrl(value: String) { viewModelScope.launch { settings.setIndexerUrl(value) } }
    fun setPushIndexerUrl(value: String) { /* TODO */ }
    fun setKnsApiUrl(value: String) { viewModelScope.launch { settings.setKnsApiUrl(value) } }
    fun setKaspaRestApiUrl(value: String) { viewModelScope.launch { settings.setKaspaRestUrl(value) } }
    fun setDiscoverNewPeers(value: Boolean) { _discoverNewPeers.value = value }
}
