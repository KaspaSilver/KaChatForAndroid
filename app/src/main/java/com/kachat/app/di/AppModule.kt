package com.kachat.app.di

import android.content.Context
import com.kachat.app.BuildConfig
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import com.kachat.app.repository.AppSettingsRepository
import com.kachat.app.services.database.KaChatDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import com.kachat.app.services.CoinGeckoApi
import com.kachat.app.services.KaspaRestApi
import com.kachat.app.services.KasiaIndexerApi
import com.kachat.app.services.KnsApi
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

// DataStore extension — creates a single instance per app
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "kachat_settings")

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * DataStore for app settings (network endpoints, preferences).
     * Replaces UserDefaults from iOS.
     */
    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> {
        return context.dataStore
    }

    /**
     * App settings repository — wraps DataStore with typed accessors.
     */
    @Provides
    @Singleton
    fun provideAppSettingsRepository(dataStore: DataStore<Preferences>): AppSettingsRepository {
        return AppSettingsRepository(dataStore)
    }

    /**
     * Room database — local message and contact storage.
     * Equivalent to Core Data in the iOS app.
     */
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): KaChatDatabase {
        return Room.databaseBuilder(
            context,
            KaChatDatabase::class.java,
            "kachat.db"
        )
            .addMigrations(KaChatDatabase.MIGRATION_15_16, KaChatDatabase.MIGRATION_16_17, KaChatDatabase.MIGRATION_17_18, KaChatDatabase.MIGRATION_18_19)
            // Safety net only, for version jumps that don't have an explicit Migration above —
            // every future schema change should get a real Migration instead of relying on this,
            // since it silently wipes every user's local contacts/messages.
            .fallbackToDestructiveMigration()
            .build()
    }

    /**
     * OkHttp client for REST API calls (Kaspa REST, Kasia Indexer, KNS API).
     * Phase 3 will add auth interceptors and node-failover logic.
     */
    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .addInterceptor(
                HttpLoggingInterceptor().apply {
                    // Request/response bodies include encrypted message payloads and signed
                    // transaction data — full body logging is only useful for local debugging
                    // and must never ship in a release build (logcat is readable by anything
                    // with log access on a rooted device, or captured in bug reports).
                    level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BODY else HttpLoggingInterceptor.Level.NONE
                }
            )
            .build()
    }

    @Provides
    @Singleton
    fun provideKaspaRestApi(okHttpClient: OkHttpClient): KaspaRestApi {
        return Retrofit.Builder()
            .baseUrl("https://api.kaspa.org/") // Default, will be updated by a wrapper
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(KaspaRestApi::class.java)
    }

    @Provides
    @Singleton
    fun provideKasiaIndexerApi(okHttpClient: OkHttpClient): KasiaIndexerApi {
        return Retrofit.Builder()
            .baseUrl("https://api.kasia.io/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(KasiaIndexerApi::class.java)
    }

    @Provides
    @Singleton
    fun provideKnsApi(okHttpClient: OkHttpClient): KnsApi {
        return Retrofit.Builder()
            .baseUrl("https://api.kns.kaspa.org/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(KnsApi::class.java)
    }

    @Provides
    @Singleton
    fun provideCoinGeckoApi(okHttpClient: OkHttpClient): CoinGeckoApi {
        return Retrofit.Builder()
            .baseUrl("https://api.coingecko.com/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(CoinGeckoApi::class.java)
    }
}
