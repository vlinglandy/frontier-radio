package com.aireadio.app

import android.app.Application
import androidx.room.Room
import androidx.work.*
import com.aireadio.app.content.*
import com.aireadio.app.data.*
import com.aireadio.app.playback.SpeechEngine
import com.aireadio.app.playback.NarrationPipeline
import com.aireadio.app.playback.OfflineNarrationPipeline
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

class RadioApp : Application() {
    lateinit var repository: Repository
    lateinit var settings: SettingsStore
    val preparer by lazy { ChinesePreparer(repository) }
    val speech by lazy { SpeechEngine(this, settings) }
    var narration: NarrationPipeline = NarrationPipeline { article, review, speed ->
        OfflineNarrationPipeline(preparer, speech).prepare(article, review, speed)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
        val db = Room.databaseBuilder(this, RadioDatabase::class.java, "radio.db")
            .addMigrations(object : androidx.room.migration.Migration(1, 2) {
                override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE articles ADD COLUMN scriptVersion INTEGER NOT NULL DEFAULT 0")
                }
            }).build()
        repository = Repository(db.dao(), settings, NetworkGate(this), FeedClient())
        scope.launch {
            settings.settings.collect { prefs ->
                val constraints = Constraints.Builder().setRequiredNetworkType(
                    if (prefs.network == NetworkPolicy.WIFI_ONLY) NetworkType.UNMETERED else NetworkType.CONNECTED).build()
                val work = PeriodicWorkRequestBuilder<RefreshWorker>(prefs.refreshMinutes.toLong(), TimeUnit.MINUTES)
                    .setConstraints(constraints).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES).build()
                WorkManager.getInstance(this@RadioApp).enqueueUniquePeriodicWork("refresh-subscriptions", ExistingPeriodicWorkPolicy.UPDATE, work)
            }
        }
    }
}
class RefreshWorker(context: android.content.Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        (applicationContext as RadioApp).repository.refresh()
        Result.success()
    } catch (cancel: CancellationException) { throw cancel }
    catch (_: Exception) { Result.retry() }
}
