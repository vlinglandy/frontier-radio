package com.aireadio.app.playback

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aireadio.app.RadioApp
import com.aireadio.app.data.Article
import com.aireadio.app.data.RadioSettings
import com.aireadio.app.data.VoiceBackend
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OfflineNarrationTest {
    private val app = ApplicationProvider.getApplicationContext<RadioApp>()
    @Test fun actualDownloadedModelTranslatesFactualText(): Unit = runBlocking {
        app.preparer.check()
        if (!app.preparer.state.value.translationReady) app.preparer.prepareModels()
        val now = System.currentTimeMillis()
        val article = Article("translation-test", "openai", "OpenAI", "OpenAI API research update",
            "https://example.com/test", now, now,
            body = "OpenAI published an API update for developers. The original article describes model evaluation and practical testing. It explains that developers should compare measured results before choosing a model. These are the facts from the article, without additional predictions.")
        val scripted = withTimeout(60_000) { app.preparer.script(article) }
        assertTrue(scripted.spokenText.contains("OpenAI"))
        assertTrue(scripted.spokenText.contains("API"))
        assertTrue(scripted.spokenText.count { it in '\u4E00'..'\u9FFF' } > 50)
        println("Offline translation: ${scripted.spokenText}")
    }
    @Test fun actualChineseSystemVoiceProducesPlayableWave(): Unit = runBlocking {
        assumeTrue("Install a Chinese offline system voice before this device test", app.speech.check())
        val now = System.currentTimeMillis()
        val article = Article("speech-test", "openai", "OpenAI", "中文语音验证", "https://example.com/test", now, now,
            spokenText = "来自 OpenAI，这是中文离线语音测试。我们根据原文整理重点，帮助你了解人工智能的最新研究。")
        val file = withTimeout(60_000) { app.speech.synthesize(article, false, 1f) }
        assertTrue(file.length() > 44)
        assertEquals("RIFF", file.inputStream().use { String(it.readBytes().take(4).toByteArray()) })
        println("Chinese system TTS: ${file.length()} bytes")
    }
    @Test fun actualKokoroFemaleAndMaleVoicesProducePlayableAudio(): Unit = runBlocking {
        // Gradle may reinstall/clear the app. Keep the verified fixture outside its data directory.
        if (!app.speech.kokoro.ready()) {
            val fixture = java.io.File("/data/local/tmp/ai-radio-model")
            if (fixture.isDirectory) fixture.copyRecursively(app.speech.kokoro.directory, overwrite = true)
        }
        assertTrue("Install the verified model fixture before this integration test", app.speech.kokoro.ready())
        val article = Article("neural-test", "qbitai", "量子位", "自然音色测试", "https://example.com/test", 0, 0,
            spokenText = "这是中文自然语音。学习智能体，掌握实际用法。")
        val paths = mutableSetOf<String>()
        for (speaker in listOf(3, 58)) {
            val file = withTimeout(120_000) { app.speech.synthesize(article, false, 1f, RadioSettings(voiceBackend = VoiceBackend.KOKORO, speakerId = speaker)) }
            val pcm = PcmWave.read(file)
            assertEquals(24000, pcm.rate)
            assertTrue(pcm.bytes.size > 24000)
            assertEquals("Neural synthesis must not silently fall back", "正在使用中文自然音色", app.speech.state.value.message)
            paths += file.path
            println("Kokoro speaker $speaker: ${file.length()} bytes")
        }
        assertEquals(2, paths.size)
    }
    @Test fun missingNaturalModelFallsBackToActualSystemVoice(): Unit = runBlocking {
        val isolated = java.io.File(app.cacheDir, "missing-model-test").apply { deleteRecursively(); mkdirs() }
        val context = object : android.content.ContextWrapper(app) {
            override fun getFilesDir() = java.io.File(isolated, "files").apply { mkdirs() }
            override fun getCacheDir() = java.io.File(isolated, "cache").apply { mkdirs() }
        }
        val speech = SpeechEngine(context, app.settings)
        try {
            val article = Article("fallback-test", "qbitai", "量子位", "回退验证", "https://example.com/test", 0, 0, spokenText = "这是系统中文语音回退测试。")
            val file = withTimeout(60_000) { speech.synthesize(article, false, 1f, RadioSettings(voiceBackend = VoiceBackend.KOKORO)) }
            assertTrue(PcmWave.read(file).bytes.isNotEmpty())
            assertTrue(speech.state.value.message.contains("系统中文语音"))
        } finally { speech.close(); isolated.deleteRecursively() }
    }
}
