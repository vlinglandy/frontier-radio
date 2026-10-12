package com.aireadio.app.ui

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aireadio.app.RadioApp
import com.aireadio.app.content.ContentRules
import com.aireadio.app.data.*
import com.aireadio.app.playback.RadioService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.*

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class RadioViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as RadioApp
    val settings = app.settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), RadioSettings())
    val articles = app.repository.dao.observeArticles().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val sourceStatus = app.repository.dao.observeSources().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val playback = RadioService.snapshot
    val preparation = app.preparer.state
    val voice = app.speech.state
    val voiceDownload = app.speech.kokoro.state
    val previewing = MutableStateFlow(false)
    val refreshing = app.repository.refreshing
    val refreshMessage = app.repository.refreshMessage
    val error = MutableStateFlow("")
    val starting = MutableStateFlow(false)
    private var startJob: Job? = null
    private var downloadJob: Job? = null
    private var previewJob: Job? = null
    private var previewPlayer: ExoPlayer? = null
    init { checkReadiness() }
    fun checkReadiness() { viewModelScope.launch {
        try { app.preparer.check(); app.speech.check() }
        catch (cancel: CancellationException) { throw cancel }
        catch (e: Exception) { error.value = e.message.orEmpty() }
    } }
    fun prepare() { viewModelScope.launch {
        try { app.speech.check(); app.preparer.prepareModels() }
        catch (cancel: CancellationException) { throw cancel }
        catch (e: Exception) { error.value = e.message.orEmpty() }
    } }
    fun toggle() {
        stopPreview()
        if (starting.value) { startJob?.cancel(); starting.value = false; return }
        if (playback.value.active) { command(RadioService.PAUSE); return }
        starting.value = true
        startJob = viewModelScope.launch {
            try {
                if (!app.speech.check()) error(app.speech.state.value.message)
                command(RadioService.START)
            } catch (cancel: CancellationException) { throw cancel }
            catch (e: Exception) { error.value = e.message ?: "播放启动失败" }
            finally { starting.value = false }
        }
    }
    fun next() = command(RadioService.NEXT)
    fun playArticle(id: String) {
        stopPreview()
        startJob?.cancel(); starting.value = false
        command(RadioService.PLAY_ARTICLE, id)
    }
    fun replay() { if (playback.value.canReplay && !playback.value.preparing) { stopPreview(); command(RadioService.REPLAY) } }
    fun downloadVoice(policy: NetworkPolicy) {
        if (downloadJob?.isActive == true) return
        downloadJob = viewModelScope.launch {
            try { app.speech.kokoro.download(app.repository.gate, policy); app.speech.check() }
            catch (cancel: CancellationException) { throw cancel }
            catch (e: Exception) { error.value = e.message.orEmpty() }
        }
    }
    fun cancelVoiceDownload() { downloadJob?.cancel() }
    fun deleteVoice() {
        cancelVoiceDownload(); stopPreview()
        viewModelScope.launch {
            try { app.speech.kokoro.delete(); app.settings.save(app.settings.current().copy(voiceBackend = VoiceBackend.SYSTEM)); app.speech.check() }
            catch (e: Exception) { error.value = e.message.orEmpty() }
        }
    }
    fun previewVoice(prefs: RadioSettings) {
        stopPreview()
        if (playback.value.active) command(RadioService.PAUSE)
        previewing.value = true
        previewJob = viewModelScope.launch {
            try {
                val sample = Article("voice-preview", "preview", "音色试听", "音色试听", "https://example.com/preview", 0, 0,
                    spokenText = "欢迎收听 AI 前沿电台。今天，一起学习智能体与大语言模型的实际用法。你可以点击文章播放，也可以重播值得细听的内容。")
                val file = app.speech.synthesize(sample, false, prefs.speed, prefs)
                ensureActive()
                previewPlayer = ExoPlayer.Builder(app).build().apply {
                    setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
                    setHandleAudioBecomingNoisy(true)
                    addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) stopPreview() }
                        override fun onPlayerError(error: PlaybackException) { stopPreview() }
                        override fun onPlayWhenReadyChanged(ready: Boolean, reason: Int) {
                            if (!ready && reason in listOf(Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY)) stopPreview()
                        }
                    })
                    setMediaItem(MediaItem.fromUri(file.path)); prepare(); play()
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (e: Exception) { previewing.value = false; error.value = e.message.orEmpty() }
        }
    }
    fun stopPreview() { previewJob?.cancel(); previewPlayer?.release(); previewPlayer = null; previewing.value = false }
    override fun onCleared() { stopPreview(); super.onCleared() }
    fun stop() { startJob?.cancel(); starting.value = false; command(RadioService.STOP) }
    private fun command(action: String, articleId: String? = null) {
        val intent = Intent(app, RadioService::class.java).setAction(action)
        articleId?.let { intent.putExtra(RadioService.ARTICLE_ID, it) }
        try {
            if (action in setOf(RadioService.START, RadioService.PLAY_ARTICLE, RadioService.REPLAY)) ContextCompat.startForegroundService(app, intent)
            else if (playback.value.active || playback.value.article != null) app.startService(intent)
        } catch (e: Exception) { error.value = e.message ?: "无法启动后台播放" }
    }
    fun refresh() { viewModelScope.launch {
        try { app.repository.refresh(force = true) }
        catch (cancel: CancellationException) { throw cancel }
        catch (e: Exception) { error.value = e.message.orEmpty() }
    } }
    fun save(settings: RadioSettings) { viewModelScope.launch {
        try { app.settings.save(settings) } catch (e: Exception) { error.value = e.message.orEmpty() }
    } }
    fun eligibleCount() = ContentRules.ranked(articles.value, settings.value, System.currentTimeMillis()).size
}
