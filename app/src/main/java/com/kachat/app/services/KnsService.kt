package com.kachat.app.services

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class KnsService @Inject constructor(
    private val networkService: NetworkService
) {
    suspend fun resolve(domain: String): String? {
        val api = networkService.knsApi.value ?: return null
        return try {
            val response = api.resolveName(domain)
            response.address
        } catch (e: Exception) {
            null
        }
    }

    suspend fun reverseResolve(address: String): String? {
        val api = networkService.knsApi.value ?: return null
        return try {
            val response = api.reverseResolve(address)
            response.name
        } catch (e: Exception) {
            null
        }
    }
}
