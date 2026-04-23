package com.kachat.app.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kachat.app.repository.AppSettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Settings screen.
 * Reads/writes app settings via AppSettingsRepository.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: AppSettingsRepository
) : ViewModel() {

    val network = settings.network
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettingsRepository.DEFAULT_NETWORK)

    val indexerUrl = settings.indexerUrl
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettingsRepository.DEFAULT_INDEXER_URL)

    val knsApiUrl = settings.knsApiUrl
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettingsRepository.DEFAULT_KNS_API_URL)

    val kaspaRestUrl = settings.kaspaRestUrl
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettingsRepository.DEFAULT_KASPA_REST_URL)

    fun saveNetwork(value: String) = viewModelScope.launch { settings.setNetwork(value) }
    fun saveIndexerUrl(value: String) = viewModelScope.launch { settings.setIndexerUrl(value) }
    fun saveKnsApiUrl(value: String) = viewModelScope.launch { settings.setKnsApiUrl(value) }
    fun saveKaspaRestUrl(value: String) = viewModelScope.launch { settings.setKaspaRestUrl(value) }
}
