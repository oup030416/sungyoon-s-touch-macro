package com.sungyoon.helper.feedback

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.sungyoon.helper.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

object FeedbackRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var sharedOutbox: FeedbackOutbox? = null
    val configured: Boolean get() = FeedbackProtocol.validEndpoint(BuildConfig.FEEDBACK_ENDPOINT)

    private fun outbox(context: Context): FeedbackOutbox = sharedOutbox ?: synchronized(this) {
        sharedOutbox ?: FeedbackOutbox(PreferenceDataStoreFactory.create {
            File(context.applicationContext.noBackupFilesDir, "feedback.preferences_pb")
        }).also { sharedOutbox = it }
    }

    sealed interface Submission {
        data object Queued : Submission
        data object Full : Submission
        data object SaveFailed : Submission
        data object ScheduleFailed : Submission
    }

    /** Finite writes/scheduling outlive their modal so dismissal cannot interrupt a submitted message. */
    fun submit(context: Context, message: FeedbackMessage, completion: (Submission) -> Unit) {
        val app = context.applicationContext
        scope.launch {
            val result = try {
                check(configured)
                outbox(app).append(message)
                try {
                    schedule(app, message.id)
                    Submission.Queued
                } catch (_: Exception) {
                    Submission.ScheduleFailed
                }
            } catch (_: FeedbackQueueFullException) {
                Submission.Full
            } catch (_: Exception) {
                Submission.SaveFailed
            }
            withContext(Dispatchers.Main.immediate) { completion(result) }
        }
    }

    fun recover(context: Context) {
        if (!configured) return
        val app = context.applicationContext
        scope.launch {
            try {
                outbox(app).pending().forEach { message ->
                    try { schedule(app, message.id) } catch (_: Exception) { /* Retry at the next startup. */ }
                }
            } catch (_: Exception) { /* Preserve unreadable data and never log feedback content. */ }
        }
    }

    private fun schedule(context: Context, id: String) {
        val work = OneTimeWorkRequestBuilder<FeedbackWorker>()
            .setInputData(workDataOf(FeedbackWorker.MESSAGE_ID to id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("feedback-$id", ExistingWorkPolicy.KEEP, work)
            .result.get(30, TimeUnit.SECONDS)
    }

    internal suspend fun deliver(context: Context, id: String): Boolean =
        FeedbackDelivery(outbox(context), AppsScriptFeedbackTransport(BuildConfig.FEEDBACK_ENDPOINT)).deliver(id)
}

class FeedbackWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val id = inputData.getString(MESSAGE_ID) ?: return@withContext Result.failure()
        try {
            if (FeedbackRuntime.deliver(applicationContext, id)) Result.success() else Result.retry()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Result.retry()
        }
    }
    companion object { const val MESSAGE_ID = "message_id" }
}
