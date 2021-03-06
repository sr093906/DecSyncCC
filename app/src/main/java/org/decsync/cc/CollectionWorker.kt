package org.decsync.cc

import android.app.PendingIntent
import android.content.ContentProviderClient
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.work.*
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.decsync.cc.calendars.CalendarsWorker
import org.decsync.cc.contacts.ContactsWorker
import org.decsync.cc.tasks.TasksWorker
import java.lang.Exception
import java.util.concurrent.TimeUnit

abstract class CollectionWorker(val context: Context, params: WorkerParameters): CoroutineWorker(context, params) {

    abstract val notificationTitleResId: Int
    abstract fun getCollectionInfo(id: String, name: String): CollectionInfo
    abstract fun sync(info: CollectionInfo, provider: ContentProviderClient): Boolean

    @ExperimentalStdlibApi
    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_ID) ?: return Result.failure()
        val name = inputData.getString(KEY_NAME) ?: return Result.failure()

        val info = getCollectionInfo(id, name)
        Log.d(TAG, "Sync collection ${info.syncType}-${info.id}")

        val provider = info.getProviderClient(context) ?: return Result.failure()
        try {
            val isInitSync = PrefUtils.getIsInitSync(context, info)
            if (isInitSync) {
                val notification = initSyncNotificationBuilder(context).apply {
                    setSmallIcon(R.drawable.ic_notification)
                    setContentTitle(context.getString(notificationTitleResId, info.name))
                }.build()
                setForeground(ForegroundInfo(info.notificationId, notification))

                val decsyncDir = PrefUtils.getNativeFile(context) ?: return Result.failure()
                val decsync = getDecsync(info, context, decsyncDir)
                val extra = Extra(info, context, provider)
                setNumProcessedEntries(extra, 0)
                decsync.initStoredEntries()
                decsync.executeStoredEntriesForPathPrefix(listOf("resources"), extra)
                PrefUtils.putIsInitSync(context, info, false)
                return Result.success()
            } else {
                return try {
                    val success = sync(info, provider)
                    if (success) Result.success() else Result.failure()
                } catch (e: Exception) {
                    Log.e(TAG, "", e)
                    val builder = errorNotificationBuilder(context).apply {
                        setSmallIcon(R.drawable.ic_notification)
                        if (PrefUtils.getUpdateForcesSaf(context)) {
                            setContentTitle(context.getString(R.string.notification_saf_update_title))
                            setContentText(context.getString(R.string.notification_saf_update_message))
                        } else {
                            setContentTitle("DecSync")
                            setContentText(e.message)
                        }
                        setContentIntent(PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), 0))
                        setAutoCancel(true)
                    }
                    with(NotificationManagerCompat.from(context)) {
                        notify(0, builder.build())
                    }
                    Result.failure()
                }
            }
        } finally {
            if (Build.VERSION.SDK_INT >= 24)
                provider.close()
            else
                @Suppress("DEPRECATION")
                provider.release()
        }
    }

    companion object {
        const val KEY_ID = "KEY_ID"
        const val KEY_NAME = "KEY_NAME"

        @ExperimentalStdlibApi
        fun enqueue(context: Context, info: CollectionInfo) {
            val workManager = WorkManager.getInstance(context)
            GlobalScope.launch {
                val workInfos = workManager.getWorkInfosForUniqueWork("${info.syncType}-${info.id}").await()
                if (workInfos.all { it.state != WorkInfo.State.RUNNING }) {
                    val inputData = Data.Builder()
                            .putString(KEY_ID, info.id)
                            .putString(KEY_NAME, info.name)
                            .build()
                    val workerClass = when (info) {
                        is AddressBookInfo -> ContactsWorker::class
                        is CalendarInfo -> CalendarsWorker::class
                        is TaskListInfo -> TasksWorker::class
                    }
                    // Set the repeatInterval slightly higher than the frequency of the SyncAdapter
                    // (90 vs 60 minutes), so we reduce the chance of two syncs occurring very close
                    // to each other.
                    val workRequest = PeriodicWorkRequest.Builder(workerClass.java, 90, TimeUnit.MINUTES)
                            .setInputData(inputData)
                            .build()
                    workManager.enqueueUniquePeriodicWork("${info.syncType}-${info.id}", ExistingPeriodicWorkPolicy.REPLACE, workRequest)
                }
            }
        }

        fun dequeue(context: Context, info: CollectionInfo) {
            WorkManager.getInstance(context).cancelUniqueWork("${info.syncType}-${info.id}")
        }
    }
}