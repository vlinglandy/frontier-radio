package com.aireadio.app.playback

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.*
import com.aireadio.app.MainActivity
import com.aireadio.app.R
import com.aireadio.app.RadioApp
import com.aireadio.app.content.ContentRules
import com.aireadio.app.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import android.os.Bundle
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

data class PlaybackSnapshot(
    val article: Article? = null, val active: Boolean = false, val playing: Boolean = false,
    val preparing: Boolean = false, val review: Boolean = false, val position: Long = 0,
    val duration: Long = 0, val message: String = "准备好，听见 AI 的下一步",
    val canReplay: Boolean = false, val retryArticleId: String? = null
)
@androidx.annotation.OptIn(UnstableApi::class)
class RadioService : MediaSessionService() {
    companion object {
        const val START = "radio.START"
        const val PAUSE = "radio.PAUSE"
        const val NEXT = "radio.NEXT"
        const val STOP = "radio.STOP"
        const val PLAY_ARTICLE = "radio.PLAY_ARTICLE"
        const val REPLAY = "radio.REPLAY"
        const val ARTICLE_ID = "article_id"
        val snapshot = MutableStateFlow(PlaybackSnapshot())
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val app get() = application as RadioApp
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaSession
    private var active = false
    private var current: Article? = null
    private var currentFile: File? = null
    private var advanceJob: Job? = null
    private var prefetchJob: Job? = null
    private var generation = 0L
    private var replayWithoutCounting = false
    private val prepared = mutableMapOf<String, Prepared>()
    private val excluded = mutableSetOf<String>()
    private val failedUntil = mutableMapOf<String, Long>()
    private lateinit var preparationLock: PowerManager.WakeLock
    private data class Prepared(val article: Article, val file: File, val speed: Float, val review: Boolean, val voiceKey: String)

    override fun onCreate() {
        super.onCreate()
        preparationLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:narration")
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("radio", "AI 电台播放", NotificationManager.IMPORTANCE_LOW))
        setMediaNotificationProvider(DefaultMediaNotificationProvider.Builder(this).setNotificationId(41).setChannelId("radio").build())
        player = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
            setHandleAudioBecomingNoisy(true)
            setWakeMode(C.WAKE_MODE_LOCAL)
        }
        val controls = object : ForwardingPlayer(player) {
            override fun play() { startRadio() }
            override fun pause() { pauseRadio() }
            override fun setPlayWhenReady(playWhenReady: Boolean) { if (playWhenReady) startRadio() else pauseRadio() }
            override fun stop() { stopRadio() }
            override fun seekToNext() { skip() }
            override fun seekToNextMediaItem() { skip() }
            override fun getAvailableCommands(): Player.Commands = super.getAvailableCommands().buildUpon()
                .add(Player.COMMAND_SEEK_TO_NEXT).add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM).build()
            override fun isCommandAvailable(command: Int) = availableCommands.contains(command)
        }
        val intent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val replayCommand = SessionCommand(REPLAY, Bundle.EMPTY)
        session = MediaSession.Builder(this, controls).setSessionActivity(intent)
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailableSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon().add(replayCommand).build()).build()
                }
                override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
                    if (customCommand.customAction == REPLAY) { replay(); return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS)) }
                    return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
                }
            })
            .setCustomLayout(listOf(CommandButton.Builder().setDisplayName("重播本条").setIconResId(android.R.drawable.ic_media_rew).setSessionCommand(replayCommand).setEnabled(false).build()))
            .build()
        // Intent-based UI controls do not bind a MediaController: register explicitly so
        // MediaSessionService creates and updates the media notification in normal use.
        addSession(session)
        player.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady && (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS || reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY)) pauseRadio()
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                snapshot.value = snapshot.value.copy(playing = isPlaying, preparing = player.playbackState == Player.STATE_BUFFERING)
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED && active) {
                    val ended = current
                    val token = ++generation
                    val skipCount = replayWithoutCounting
                    advanceJob?.cancel()
                    prefetchJob?.cancel()
                    advanceJob = scope.launch {
                        if (ended != null) {
                            if (!skipCount) app.repository.dao.heard(ended.id, System.currentTimeMillis())
                            excluded += ended.id
                        }
                        ensureActive()
                        if (token != generation) return@launch
                        current = null
                        buildNext(resume = false)
                    }
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                current?.let { excluded += it.id }
                snapshot.value = snapshot.value.copy(message = "当前音频播放失败，正在切换")
                if (active) advance(resume = false)
            }
        })
        scope.launch {
            while (isActive) {
                delay(5_000)
                current?.let { article ->
                    app.repository.dao.progress(article.id, player.currentPosition.coerceAtLeast(0))
                    snapshot.value = snapshot.value.copy(position = player.currentPosition.coerceAtLeast(0), duration = player.duration.coerceAtLeast(0))
                }
            }
        }
        scope.launch {
            while (isActive) {
                val minutes = app.settings.current().refreshMinutes
                delay(minutes * 60_000L)
                if (active) {
                    app.repository.refresh()
                    if (player.playbackState == Player.STATE_IDLE && advanceJob?.isActive != true) advance(false)
                }
            }
        }
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = session
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            START -> startRadio()
            PAUSE -> pauseRadio()
            NEXT -> skip()
            STOP -> stopRadio()
            PLAY_ARTICLE -> intent.getStringExtra(ARTICLE_ID)?.let(::playArticle)
            REPLAY -> if (current != null) replay() else { bootstrapForeground(); stopRadio() }
        }
        return START_NOT_STICKY
    }
    private fun bootstrapForeground() {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, RadioService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, "radio").setSmallIcon(R.drawable.ic_stat_radio)
            .setContentTitle("AI 前沿电台").setContentText("正在准备中文内容…")
            .setContentIntent(open).addAction(0, "停止", stop).setOngoing(true).setOnlyAlertOnce(true).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(41, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(41, notification)
    }
    private fun startRadio() {
        if (active && (player.isPlaying || advanceJob?.isActive == true)) return
        bootstrapForeground()
        active = true
        snapshot.value = snapshot.value.copy(active = true, message = "正在准备播放…")
        scope.launch { app.repository.refresh() }
        if (player.playbackState == Player.STATE_READY) { player.play(); prefetch() }
        else advance(resume = true)
    }
    private fun pauseRadio() {
        generation++
        active = false
        advanceJob?.cancel(); prefetchJob?.cancel()
        player.pause()
        saveProgress()
        snapshot.value = snapshot.value.copy(active = false, playing = false, preparing = false, message = "已暂停，再次播放可继续")
        stopForeground(STOP_FOREGROUND_DETACH)
    }
    private fun stopRadio() {
        pauseRadio()
        replayControl(false)
        snapshot.value = snapshot.value.copy(canReplay = false, retryArticleId = null)
        player.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    private fun skip() {
        if (!active) return
        current?.let { excluded += it.id }
        saveProgress()
        current = null
        player.stop()
        advance(false)
    }
    private fun advance(resume: Boolean) {
        generation++
        advanceJob?.cancel()
        prefetchJob?.cancel()
        advanceJob = scope.launch { buildNext(resume) }
    }
    private fun playArticle(id: String) {
        val token = ++generation
        advanceJob?.cancel(); prefetchJob?.cancel()
        saveProgress()
        player.stop(); player.clearMediaItems()
        current = null; currentFile = null
        replayWithoutCounting = false
        active = true
        bootstrapForeground()
        replayControl(false)
        snapshot.value = PlaybackSnapshot(active = true, preparing = true, message = "正在准备这篇文章…")
        advanceJob = scope.launch {
            preparationLock.acquire(10 * 60_000L)
            try {
                val article = app.repository.dao.article(id) ?: error("这篇文章已不存在，请刷新列表")
                snapshot.value = snapshot.value.copy(article = article)
                val prefs = app.settings.current()
                val review = article.playCount > 0
                val cached = prepared.remove(id)?.takeIf { it.speed == prefs.speed && it.review == review && it.voiceKey == voiceKey(prefs) && it.file.exists() }
                val ready = cached ?: app.narration.prepare(article, review, prefs.speed).let { Prepared(it.article, it.file, prefs.speed, review, voiceKey(prefs)) }
                ensureActive()
                if (token != generation || !active) return@launch
                app.repository.dao.progress(id, 0)
                install(ready, 0)
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                if (token == generation) {
                    app.repository.dao.error(id, error.message.orEmpty().take(250))
                    active = false
                    snapshot.value = snapshot.value.copy(active = false, preparing = false, playing = false, canReplay = false,
                        retryArticleId = id, message = "这篇文章准备失败：${error.message}。点击重试")
                    stopForeground(STOP_FOREGROUND_DETACH)
                }
            } finally { if (preparationLock.isHeld) preparationLock.release() }
        }
    }
    private fun replay() {
        val article = current ?: return
        if (currentFile?.exists() != true || snapshot.value.preparing) return
        generation++
        advanceJob?.cancel(); prefetchJob?.cancel()
        bootstrapForeground()
        active = true
        replayWithoutCounting = article.playCount > 0
        player.seekTo(0); player.play()
        scope.launch { app.repository.dao.progress(article.id, 0) }
        snapshot.value = snapshot.value.copy(active = true, position = 0, message = "正在重播本条", retryArticleId = null)
        prefetch()
    }
    private fun voiceKey(prefs: RadioSettings) = app.speech.cacheIdentity(prefs)
    private fun replayControl(enabled: Boolean) {
        session.setCustomLayout(listOf(CommandButton.Builder().setDisplayName("重播本条").setIconResId(android.R.drawable.ic_media_rew)
            .setSessionCommand(SessionCommand(REPLAY, Bundle.EMPTY)).setEnabled(enabled).build()))
    }
    private suspend fun install(ready: Prepared, position: Long) {
        app.settings.setCurrent(ready.article.id)
        currentCoroutineContext().ensureActive()
        current = ready.article; currentFile = ready.file; replayWithoutCounting = false
        val item = MediaItem.Builder().setMediaId(ready.article.id).setUri(android.net.Uri.fromFile(ready.file))
            .setMediaMetadata(MediaMetadata.Builder().setTitle((if (ready.review) "复习 · " else "") + ready.article.title)
                .setArtist(ready.article.sourceName).setIsPlayable(true).setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST).build()).build()
        player.setMediaItem(item, position); player.prepare(); player.play()
        replayControl(true)
        snapshot.value = PlaybackSnapshot(ready.article, true, player.isPlaying, false, ready.review, position, 0,
            if (ready.review) "正在复习 · 可点选其他文章" else "正在播放 · 可随时点选文章", canReplay = true)
        prefetch()
    }
    private suspend fun buildNext(resume: Boolean) {
        preparationLock.acquire(10 * 60_000L)
        try { buildNextContent(resume) }
        finally { if (preparationLock.isHeld) preparationLock.release() }
    }
    private suspend fun buildNextContent(resume: Boolean) {
        if (!active) return
        replayControl(false)
        snapshot.value = snapshot.value.copy(active = true, playing = false, preparing = true, message = "正在整理下一条中文消息…")
        val prefs = app.settings.current()
        var articles = app.repository.dao.articles()
        if (articles.none { ContentRules.eligible(it, prefs, System.currentTimeMillis()) && it.playCount == 0 }) {
            app.repository.refresh()
            articles = app.repository.dao.articles()
        }
        val remembered = if (resume) app.repository.dao.article(app.settings.currentId())?.takeIf {
            ContentRules.eligible(it, prefs, System.currentTimeMillis()) && it.positionMs > 0
        } else null
        failedUntil.entries.removeAll { it.value <= System.currentTimeMillis() }
        val unavailable = failedUntil.keys.toSet()
        var selected = remembered?.takeIf { it.id !in unavailable }
            ?: ContentRules.nextInCycle(articles, prefs, System.currentTimeMillis(), excluded, unavailable)
        val failed = mutableSetOf<String>()
        while (selected != null && active) {
            val article = selected
            try {
                val review = article.playCount > 0
                val cached = prepared.remove(article.id)?.takeIf { it.speed == prefs.speed && it.review == review && it.voiceKey == voiceKey(prefs) && it.file.exists() }
                val ready = cached ?: run {
                    val narration = app.narration.prepare(article, review, prefs.speed)
                    Prepared(narration.article, narration.file, prefs.speed, review, voiceKey(prefs))
                }
                if (!active) return
                currentCoroutineContext().ensureActive()
                install(ready, if (remembered?.id == article.id) remembered.positionMs else 0)
                return
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                app.repository.dao.error(article.id, error.message.orEmpty().take(250))
                failed += article.id
                failedUntil[article.id] = System.currentTimeMillis() + prefs.refreshMinutes * 60_000L
                snapshot.value = snapshot.value.copy(message = "已跳过 ${article.sourceName}：${error.message}")
                selected = ContentRules.nextInCycle(articles, prefs, System.currentTimeMillis(), excluded, unavailable + failed)
            }
        }
        player.clearMediaItems()
        current = null
        snapshot.value = snapshot.value.copy(article = null, preparing = false, playing = false, canReplay = false,
            message = if (failed.isNotEmpty()) "内容暂时无法播报，请检查中文语音和翻译准备情况" else "暂无符合条件的内容，等待刷新；可调整日期或来源")
        // Media3 / Android may reclaim a service that remains silent. Never auto-restart playback.
    }
    private fun prefetch() {
        prefetchJob?.cancel()
        prefetchJob = scope.launch {
            val prefs = app.settings.current()
            val all = app.repository.dao.articles()
            prepared.entries.removeAll { (_, ready) ->
                ready.speed != prefs.speed || ready.voiceKey != voiceKey(prefs) || !ContentRules.eligible(ready.article, prefs, System.currentTimeMillis())
            }
            val reserved = mutableSetOf<String>().apply { addAll(excluded); addAll(failedUntil.keys); current?.let { add(it.id) } }
            repeat(2) {
                val candidate = ContentRules.next(all, prefs, System.currentTimeMillis(), reserved) ?: return@repeat
                reserved += candidate.id
                if (!app.repository.gate.mayPrefetch(prefs.network) && candidate.spokenText.isBlank()) return@repeat
                try {
                    val review = candidate.playCount > 0
                    val narration = app.narration.prepare(candidate, review, prefs.speed)
                    prepared[candidate.id] = Prepared(narration.article, narration.file, prefs.speed, review, voiceKey(prefs))
                } catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { /* Preparing ahead must not interrupt current playback. */ }
            }
            app.speech.evict(all.filter { it.playCount > 0 }.map { it.id }.toSet(),
                prepared.values.map { it.file }.toSet() + listOfNotNull(currentFile))
        }
    }
    private fun saveProgress() {
        val article = current ?: return
        val position = player.currentPosition.coerceAtLeast(0)
        scope.launch { app.repository.dao.progress(article.id, position) }
    }
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!active) stopSelf()
    }
    override fun onDestroy() {
        val position = player.currentPosition.coerceAtLeast(0)
        val article = current
        if (article != null) CoroutineScope(Dispatchers.IO).launch { app.repository.dao.progress(article.id, position) }
        active = false
        snapshot.value = snapshot.value.copy(active = false, playing = false, preparing = false, canReplay = false, message = "播放已停止，点击播放继续")
        scope.cancel()
        if (preparationLock.isHeld) preparationLock.release()
        session.release(); player.release()
        super.onDestroy()
    }
}
