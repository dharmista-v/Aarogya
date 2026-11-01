package com.example.aarogya.classify

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.aarogya.AarogyaApp
import com.example.aarogya.BuildConfig
import com.example.aarogya.data.FoodStatus
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * Background queue for pending entries. Each run is one attempt per pending entry; WorkManager
 * re-runs with exponential backoff (30 s, 1 min, 2 min, …) until every entry is classified or has
 * used [MAX_ATTEMPTS]. Survives the app being closed and the phone restarting.
 */
class ClassifyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as AarogyaApp
        for (entry in app.store.pendingFoods()) {
            val attempt = entry.attempts + 1
            try {
                app.store.replacePending(entry.id, app.classifier.classify(entry, lastAttempt = attempt >= MAX_ATTEMPTS))
            } catch (e: CancellationException) {
                throw e
            } catch (e: NeedsUserInput) {
                // Gemini exhausted (or off): park it with on-device suggestions for the user to confirm.
                app.store.updateFood(entry.copy(attempts = attempt, status = FoodStatus.FAILED, error = e.message, suggestions = e.suggestions))
            } catch (e: Exception) {
                app.store.updateFood(
                    entry.copy(
                        attempts = attempt,
                        status = FoodStatus.PENDING, // the final attempt always ends in NeedsUserInput
                        error = e.message,
                    )
                )
            }
        }
        return if (app.store.pendingFoods().isNotEmpty()) Result.retry() else Result.success()
    }

    companion object {
        const val MAX_ATTEMPTS = 5
        private const val WORK = "classify-food"

        /** Queues a run; new entries added while one runs are picked up by the appended run. */
        fun enqueue(app: AarogyaApp) {
            val geminiOn = BuildConfig.GEMINI_API_KEY.isNotEmpty() && app.prefs.state.value.useGemini
            val request = OneTimeWorkRequestBuilder<ClassifyWorker>()
                // Gemini needs the internet; waiting for it means offline time doesn't burn attempts.
                .setConstraints(Constraints.Builder().setRequiredNetworkType(if (geminiOn) NetworkType.CONNECTED else NetworkType.NOT_REQUIRED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(app).enqueueUniqueWork(WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
