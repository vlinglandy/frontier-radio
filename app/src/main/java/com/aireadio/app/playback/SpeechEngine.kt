package com.aireadio.app.playback

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.aireadio.app.content.ContentRules
import com.aireadio.app.data.*
import com.aireadio.app.content.NarrationText
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

data class VoiceState(val ready: Boolean = false, val message: String = "检查中文离线语音…")
class SpeechEngine(context: Context, private val settings: SettingsStore = SettingsStore(context)) {
    val kokoro = KokoroEngine(context)
    fun cacheIdentity(prefs: RadioSettings) = "${prefs.voiceBackend}:${prefs.speakerId}:${kokoro.state.value.ready}"
    val state = MutableStateFlow(VoiceState())
    private val cache = File(context.cacheDir, "radio_audio").apply { mkdirs() }
    private val init = CompletableDeferred<Int>()
    private val callbacks = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val lock = Mutex()
    private val tts = TextToSpeech(context.applicationContext) { init.complete(it) }
    private var voiceName = ""
    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) { callbacks.remove(utteranceId)?.complete(Unit) }
            @Deprecated("Legacy callback") override fun onError(utteranceId: String?) {
                callbacks.remove(utteranceId)?.completeExceptionally(IllegalStateException("中文语音合成失败"))
            }
            override fun onError(utteranceId: String?, errorCode: Int) {
                callbacks.remove(utteranceId)?.completeExceptionally(IllegalStateException("中文语音合成失败，错误 $errorCode"))
            }
            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                callbacks.remove(utteranceId)?.cancel()
            }
        })
    }
    suspend fun check(): Boolean = lock.withLock {
        if (settings.current().voiceBackend == VoiceBackend.KOKORO && kokoro.ready()) {
            state.value = VoiceState(true, "中文自然音色已准备"); true
        } else checkUnlocked()
    }
    private suspend fun checkUnlocked(): Boolean {
        try {
            if (withTimeout(30_000) { init.await() } != TextToSpeech.SUCCESS) error("系统未安装可用的语音引擎")
            val support = tts.setLanguage(Locale.SIMPLIFIED_CHINESE)
            if (support == TextToSpeech.LANG_MISSING_DATA || support == TextToSpeech.LANG_NOT_SUPPORTED)
                error("请在系统语音设置中安装中文离线语音")
            val voice = tts.voices?.filter { it.locale.language == "zh" && !it.isNetworkConnectionRequired }
                ?.sortedByDescending { (if (it.locale.country == "CN") 1000 else 0) + it.quality }?.firstOrNull()
                ?: error("请在系统语音设置中下载中文离线语音包")
            tts.voice = voice
            voiceName = voice.name
            state.value = VoiceState(true, "中文离线语音已准备")
            return true
        } catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) { state.value = VoiceState(false, error.message ?: "语音引擎不可用"); return false }
    }
    suspend fun synthesize(article: Article, review: Boolean, speed: Float, override: RadioSettings? = null): File = lock.withLock {
        val prefs = override ?: settings.current()
        val text = (if (review) "复习内容。" else "") + article.spokenText
        if (prefs.voiceBackend == VoiceBackend.KOKORO && kokoro.ready()) {
            val key = ContentRules.hash("v2|kokoro-int8-v1_1|$text|$speed|${prefs.speakerId}").take(16)
            val output = File(cache, "${article.id}_$key.wav")
            if (output.exists() && output.length() > 44) {
                output.setLastModified(System.currentTimeMillis())
                state.value = VoiceState(true, "正在使用中文自然音色")
                return@withLock output
            }
            val temp = File(cache, "${article.id}_$key.part.wav")
            try {
                kokoro.synthesize(text, prefs.speakerId, speed, temp)
                check(temp.length() > 44 && temp.renameTo(output)) { "无法保存自然语音" }
                state.value = VoiceState(true, "正在使用中文自然音色")
                return@withLock output
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { state.value = VoiceState(false, "自然语音不可用，改用系统语音：${error.message}") }
            catch (error: LinkageError) { state.value = VoiceState(false, "设备不支持自然语音库，改用系统语音") }
            catch (error: OutOfMemoryError) { state.value = VoiceState(false, "设备内存不足，改用系统语音") }
            finally { temp.delete() }
        }
        val fallback = if (prefs.voiceBackend == VoiceBackend.KOKORO) "自然音色未就绪或不可用，正在使用系统中文语音" else "中文离线语音已准备"
        if (!checkUnlocked()) error(state.value.message)
        state.value = VoiceState(true, fallback)
        val key = ContentRules.hash("v2|system|$text|$speed|$voiceName").take(16)
        val output = File(cache, "${article.id}_$key.wav")
        if (output.exists() && output.length() > 44) { output.setLastModified(System.currentTimeMillis()); return@withLock output }
        val temp = File(cache, "${article.id}_$key.part.wav")
        val parts = mutableListOf<File>()
        tts.setSpeechRate(speed)
        try {
            val chunks = NarrationText.chunks(text)
            require(chunks.isNotEmpty()) { "没有可朗读的内容" }
            withTimeout(180_000) {
                for ((index, chunk) in chunks.withIndex()) {
                    val part = File(cache, "${article.id}_$key.$index.part.wav").also(parts::add)
                    val request = "speech-$key-$index-${System.nanoTime()}"
                    val complete = CompletableDeferred<Unit>()
                    callbacks[request] = complete
                    try {
                        if (tts.synthesizeToFile(chunk.text, Bundle(), part, request) != TextToSpeech.SUCCESS) error("无法启动语音合成")
                        complete.await()
                        check(part.length() > 44) { "语音引擎返回空音频" }
                    } finally { callbacks.remove(request) }
                }
            }
            withContext(Dispatchers.IO) {
                val first = PcmWave.read(parts.first())
                PcmWave.Writer(temp, first.rate, first.channels).use { wave ->
                    for ((index, part) in parts.withIndex()) {
                        ensureActive()
                        val pcm = if (index == 0) first else PcmWave.read(part)
                        require(pcm.rate == first.rate && pcm.channels == first.channels) { "系统语音音频格式发生变化" }
                        wave.raw(pcm.bytes); wave.silence(chunks[index].pauseMs)
                    }
                }
            }
            check(temp.renameTo(output)) { "无法保存离线音频" }
            output
        } catch (cancel: CancellationException) { tts.stop(); throw cancel }
        finally { parts.forEach { it.delete() }; temp.delete() }
    }
    fun evict(heardIds: Set<String>, protected: Set<File>) {
        val files = cache.listFiles()?.filter { !it.name.contains(".part.") }.orEmpty()
        var total = files.sumOf { it.length() }
        for (file in files.filter { it !in protected }.sortedWith(
            compareBy<File> { if (it.name.substringBefore('_') in heardIds) 0 else 1 }.thenBy { it.lastModified() })) {
            if (total <= 200L * 1024 * 1024) break
            val size = file.length()
            if (file.delete()) total -= size
        }
    }
    fun close() { callbacks.values.forEach { it.cancel() }; callbacks.clear(); tts.stop(); tts.shutdown() }
}
