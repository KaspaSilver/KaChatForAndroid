package com.kachat.app.services

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.kachat.app.repository.ChatRepository
import com.kachat.app.repository.GroupRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Periodic fallback for [ChatRepository.syncMessages]/[GroupRepository.syncGroups] — WorkManager
 * isn't subject to the dataSync foreground-service execution-time cap (Android 15+'s ~6h/24h
 * rolling limit, see SyncForegroundService) and survives process death, so this still gets a
 * chance to run every ~15 minutes even during the hours SyncForegroundService itself is unable to
 * restart. Doesn't touch BroadcastScanningService — broadcast messages have no queryable history
 * to catch up on, only a live block subscription, which doesn't fit a short-lived periodic job.
 * Group chat's `gcomm`/`gctl` payloads are different: both are self-stash transactions on a
 * queryable identity address, now indexed (`group-messages/by-blinded-group-id`,
 * `group-control/by-sender`), so they fit this same catch-up shape as 1:1 messages.
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val chatRepository: ChatRepository,
    private val groupRepository: GroupRepository
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            chatRepository.syncMessages()
            groupRepository.syncGroups()
            Result.success()
        } catch (e: Exception) {
            Log.w("SyncWorker", "Periodic sync failed, will retry next cycle", e)
            Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "kachat_periodic_sync"
    }
}
