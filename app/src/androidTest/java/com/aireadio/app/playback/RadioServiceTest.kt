package com.aireadio.app.playback

import android.content.ComponentName
import android.content.Intent
import android.app.Notification
import android.app.NotificationManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aireadio.app.MainActivity
import com.aireadio.app.RadioApp
import com.aireadio.app.data.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/** Real service / ExoPlayer / Room tests. Only the narration audio is a deterministic fixture. */
@RunWith(AndroidJUnit4::class)
class RadioServiceTest {
    private val app = ApplicationProvider.getApplicationContext<RadioApp>()
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var original: NarrationPipeline
    private lateinit var prefs: RadioSettings
    private val now = System.currentTimeMillis()
    private val source = "instrumentation-$now"
    private lateinit var tone: File
    @Before fun setUp() = runBlocking {
        prefs = app.settings.current()
        original = app.narration
        tone = File(app.cacheDir, "test-tone.wav")
        writeWave(tone, 8)
        app.settings.save(RadioSettings(enabledSources = setOf(source)))
        app.settings.setCurrent("")
        app.narration = NarrationPipeline { article, _, _ ->
            if (article.id.endsWith("bad")) error("test unavailable content")
            Narration(article.copy(spokenText = "测试音频"), tone)
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        if (Build.VERSION.SDK_INT >= 33) InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("pm grant com.aireadio.app android.permission.POST_NOTIFICATIONS").close()
    }
    @After fun tearDown() = runBlocking {
        app.startService(Intent(app, RadioService::class.java).setAction(RadioService.STOP))
        waitFor { !it.active }
        delay(300)
        app.narration = original
        app.settings.save(prefs)
        app.settings.setCurrent("")
        scenario.close()
    }
    private fun article(suffix: String, date: Long = now) = Article("$source-$suffix", source, "测试来源", "测试消息 $suffix",
        "https://example.com/$source/$suffix", date, now, snippet = "A deterministic playback fixture.")
    private fun start() = ContextCompat.startForegroundService(app, Intent(app, RadioService::class.java).setAction(RadioService.START))
    private fun select(id: String) = ContextCompat.startForegroundService(app, Intent(app, RadioService::class.java).setAction(RadioService.PLAY_ARTICLE).putExtra(RadioService.ARTICLE_ID, id))
    private suspend fun waitFor(predicate: (PlaybackSnapshot) -> Boolean): PlaybackSnapshot = withTimeout(35_000) {
        while (!predicate(RadioService.snapshot.value)) delay(100)
        RadioService.snapshot.value
    }
    @Test fun continuousPlaybackPauseResumeSkipAndStop(): Unit = runBlocking {
        app.repository.dao.insert(article("first", now))
        app.repository.dao.insert(article("second", now - 1000))
        start()
        waitFor { it.playing && it.article?.id == "$source-first" }
        val future = withContext(Dispatchers.Main) {
            MediaController.Builder(app, SessionToken(app, ComponentName(app, RadioService::class.java))).buildAsync()
        }
        val controller = future.get(15, TimeUnit.SECONDS)
        try {
            waitFor { it.playing && it.position >= 500 }
            withContext(Dispatchers.Main) { controller.pause() }
            waitFor { !it.active && !it.playing }
            val saved = app.repository.dao.article("$source-first")!!.positionMs
            assertTrue("Pause persisted playback position", saved > 0)
            withContext(Dispatchers.Main) { controller.play() }
            waitFor { it.playing }
            withContext(Dispatchers.Main) { controller.seekToNextMediaItem() }
            waitFor { it.playing && it.article?.id == "$source-second" }
            waitFor { it.article?.id == "$source-first" }
            waitFor { it.review }
            assertTrue(app.repository.dao.article("$source-second")!!.playCount > 0)
            withContext(Dispatchers.Main) { controller.stop() }
            waitFor { !it.active && !it.playing }
        } finally { withContext(Dispatchers.Main) { controller.release() } }
    }
    @Test fun failedItemIsSkippedAndOneItemLoopsAsReview() = runBlocking {
        app.repository.dao.insert(article("bad", now))
        app.repository.dao.insert(article("good", now - 1000))
        start()
        waitFor { it.playing && it.article?.id == "$source-good" }
        assertTrue(app.repository.dao.article("$source-bad")!!.error.contains("unavailable"))
        waitFor { it.review && it.playing && it.article?.id == "$source-good" }
        assertTrue(app.repository.dao.article("$source-good")!!.playCount > 0)
    }
    @Test fun selectedArticleBypassesOrderAndContinuesWithUnheardContent() = runBlocking {
        val first = article("first"); val picked = article("picked", now - 2000)
        app.repository.dao.insert(first); app.repository.dao.insert(picked)
        select(picked.id)
        waitFor { it.playing && it.article?.id == picked.id }
        assertEquals(0, app.repository.dao.article(first.id)!!.playCount)
        waitFor { it.playing && it.article?.id == first.id }
        assertEquals(1, app.repository.dao.article(picked.id)!!.playCount)
    }
    @Test fun intentDrivenPlaybackHasMediaNotificationAndReplayAction(): Unit = runBlocking {
        val picked = article("notification")
        app.repository.dao.insert(picked)
        select(picked.id)
        waitFor { it.playing && it.article?.id == picked.id }
        val manager = app.getSystemService(NotificationManager::class.java)
        val notification = withTimeout(15000) {
            var ready: Notification? = null
            while (ready == null) {
                ready = manager.activeNotifications.firstOrNull { it.id == 41 }?.notification?.takeIf {
                    it.extras.containsKey(Notification.EXTRA_MEDIA_SESSION) &&
                        it.actions?.any { action -> action.title.toString() == "重播本条" } == true
                }
                if (ready == null) delay(100)
            }
            ready
        }
        assertEquals(picked.title, notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        waitFor { it.position >= 500 }
        notification.actions.first { it.title.toString() == "重播本条" }.actionIntent.send()
        waitFor { it.playing && it.position < 500 && it.article?.id == picked.id }
    }
    @Test fun lastSelectionWinsEvenWhenEarlierPreparationIgnoresCancellation() = runBlocking {
        val slow = article("slow"); val fast = article("fast")
        app.repository.dao.insert(slow); app.repository.dao.insert(fast)
        app.narration = NarrationPipeline { article, _, _ ->
            if (article.id == slow.id) withContext(NonCancellable) { delay(1500) }
            Narration(article, tone)
        }
        select(slow.id); waitFor { it.preparing && it.article?.id == slow.id }
        select(fast.id); waitFor { it.playing && it.article?.id == fast.id }
        delay(2000)
        assertEquals(fast.id, RadioService.snapshot.value.article?.id)
    }
    @Test fun selectedFailureWaitsForRetryInsteadOfPlayingSomethingElse(): Unit = runBlocking {
        val bad = article("bad"); val good = article("good", now - 1000)
        app.repository.dao.insert(bad); app.repository.dao.insert(good)
        select(bad.id)
        waitFor { !it.active && it.retryArticleId == bad.id }
        delay(800); assertFalse(RadioService.snapshot.value.playing)
        app.narration = NarrationPipeline { article, _, _ -> Narration(article, tone) }
        select(bad.id); waitFor { it.playing && it.article?.id == bad.id }
    }
    @Test fun replayFromPauseResetsPositionAndDoesNotCountHistoricalArticleAgain() = runBlocking {
        val replayed = article("replayed").copy(playCount = 2, lastHeardAt = now - 500)
        val next = article("next", now - 1000)
        app.repository.dao.insert(replayed); app.repository.dao.insert(next)
        select(replayed.id)
        waitFor { it.playing && it.position >= 500 }
        app.startService(Intent(app, RadioService::class.java).setAction(RadioService.PAUSE))
        waitFor { !it.active }
        ContextCompat.startForegroundService(app, Intent(app, RadioService::class.java).setAction(RadioService.REPLAY))
        waitFor { it.playing && it.position < 500 }
        waitFor { it.playing && it.article?.id == next.id }
        assertEquals(2, app.repository.dao.article(replayed.id)!!.playCount)
    }
    @Test fun waitingCanResumeAndNewContentJoinsAtBoundary() = runBlocking {
        start()
        waitFor { it.active && !it.preparing && it.article == null }
        app.repository.dao.insert(article("one"))
        app.startService(Intent(app, RadioService::class.java).setAction(RadioService.NEXT))
        waitFor { it.playing && it.article?.id == "$source-one" }
        app.repository.dao.insert(article("new", now + 1))
        waitFor { it.playing && it.article?.id == "$source-new" }
        assertTrue(app.repository.dao.article("$source-one")!!.playCount > 0)
    }
    @Test fun lockedScreenContinuesPlayingAndSwitchingForOneMinute(): Unit = runBlocking {
        app.repository.dao.insert(article("locked"))
        start()
        waitFor { it.playing && it.article?.id == "$source-locked" }
        fun shell(command: String) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).close()
        }
        try {
            shell("input keyevent 223")
            delay(65_000)
            val heard = app.repository.dao.article("$source-locked")!!
            assertTrue("Playback kept switching while locked", heard.playCount >= 5)
            assertTrue(RadioService.snapshot.value.active)
            assertTrue(RadioService.snapshot.value.review)
        } finally { shell("input keyevent 224"); shell("wm dismiss-keyguard") }
    }
    private fun writeWave(file: File, seconds: Int) {
        val sampleRate = 16000
        val samples = seconds * sampleRate
        FileOutputStream(file).use { out ->
            fun int(value: Int) { repeat(4) { out.write(value ushr (it * 8) and 255) } }
            fun short(value: Int) { out.write(value and 255); out.write(value ushr 8 and 255) }
            out.write("RIFF".toByteArray()); int(36 + samples * 2); out.write("WAVEfmt ".toByteArray())
            int(16); short(1); short(1); int(sampleRate); int(sampleRate * 2); short(2); short(16)
            out.write("data".toByteArray()); int(samples * 2)
            repeat(samples) { short((kotlin.math.sin(it * 2 * Math.PI * 440 / sampleRate) * 100).toInt()) }
        }
    }
}
