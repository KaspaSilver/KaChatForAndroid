package com.kachat.app.services

import com.kachat.app.repository.AppSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NetworkService @Inject constructor(
    private val settings: AppSettingsRepository,
    private val okHttpClient: OkHttpClient
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _kaspaRestApi = MutableStateFlow<KaspaRestApi?>(null)
    val kaspaRestApi: StateFlow<KaspaRestApi?> = _kaspaRestApi

    private val _indexerApi = MutableStateFlow<KasiaIndexerApi?>(null)
    val indexerApi: StateFlow<KasiaIndexerApi?> = _indexerApi

    private val _knsApi = MutableStateFlow<KnsApi?>(null)
    val knsApi: StateFlow<KnsApi?> = _knsApi

    init {
        observeSettings()
    }

    private fun observeSettings() {
        scope.launch {
            settings.kaspaRestUrl.collectLatest { url ->
                _kaspaRestApi.value = createApi(url)
            }
        }
        scope.launch {
            settings.indexerUrl.collectLatest { url ->
                _indexerApi.value = createApi(url)
            }
        }
        scope.launch {
            settings.knsApiUrl.collectLatest { url ->
                _knsApi.value = createApi(url)
            }
        }
    }

    private inline fun <reified T> createApi(baseUrl: String): T {
        val sanitizedUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        return Retrofit.Builder()
            .baseUrl(sanitizedUrl)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(T::class.java)
    }
}
