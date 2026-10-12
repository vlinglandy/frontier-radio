package com.aireadio.app.content

import com.aireadio.app.data.*
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout

data class PreparationState(val translationReady: Boolean = false, val preparing: Boolean = false, val message: String = "检查离线翻译模型…")
class ChinesePreparer(private val repository: Repository) {
    val state = MutableStateFlow(PreparationState())
    private val modelLock = Mutex()
    private val languageIdentifier = LanguageIdentification.getClient()
    private val translators = mutableMapOf<String, Translator>()
    suspend fun check() {
        val model = TranslateRemoteModel.Builder(TranslateLanguage.CHINESE).build()
        val ready = withTimeout(15_000) { RemoteModelManager.getInstance().isModelDownloaded(model).await() }
        if (state.value.preparing) return
        state.value = PreparationState(ready, false, if (ready) "中文离线翻译已准备" else "首次使用需要下载免费翻译模型，约数十 MB")
    }
    private fun translator(language: String) = translators.getOrPut(language) {
        Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(language)
            .setTargetLanguage(TranslateLanguage.CHINESE).build())
    }
    suspend fun prepareModels() = modelLock.withLock {
        state.value = state.value.copy(preparing = true, message = "正在准备离线翻译模型…下载期间可先配置来源")
        try {
            if (!repository.gate.mayDownloadModel(repository.settings.current().network)) {
                state.value = state.value.copy(preparing = false)
                check()
                if (!state.value.translationReady) error("请连接 Wi-Fi 下载翻译模型，或在配置中允许所有网络下载")
                return@withLock
            }
            val conditions = DownloadConditions.Builder().apply {
                if (repository.settings.current().network != NetworkPolicy.ALL) requireWifi()
            }.build()
            withTimeout(300_000) { translator(TranslateLanguage.ENGLISH).downloadModelIfNeeded(conditions).await() }
            state.value = PreparationState(true, false, "中文离线翻译已准备")
        } catch (timeout: TimeoutCancellationException) {
            state.value = PreparationState(false, false, "模型准备超时，请检查网络后重试")
            throw IllegalStateException(state.value.message, timeout)
        } catch (cancel: CancellationException) {
            state.value = state.value.copy(preparing = false, message = "模型准备已取消，可再次点击准备")
            throw cancel
        }
        catch (error: Exception) { state.value = PreparationState(false, false, error.message ?: "模型下载失败，请稍后重试"); throw error }
        finally { state.value = state.value.copy(preparing = false) }
    }
    suspend fun script(input: Article): Article {
        if (input.spokenText.isNotBlank() && input.scriptVersion == NarrationText.VERSION) return input
        val article = repository.enrich(input)
        val original = Summarizer.extract(article.body.ifBlank { article.snippet })
        if (original.isBlank()) error("此消息没有可用正文或摘要，已跳过")
        val language = languageIdentifier.identifyLanguage("${article.title}\n${original.take(1500)}").await()
        val code = if (language == "und") TranslateLanguage.ENGLISH else TranslateLanguage.fromLanguageTag(language)
            ?: error("暂不支持此内容语言：$language")
        val (title, body) = if (code == TranslateLanguage.CHINESE) article.title to original
        else {
            val engine = translator(code)
            // With no network, translate() can still use previously downloaded models.
            if (repository.gate.mayDownloadModel(repository.settings.current().network)) {
                val conditions = DownloadConditions.Builder().apply {
                    if (repository.settings.current().network != NetworkPolicy.ALL) requireWifi()
                }.build()
                withTimeout(300_000) { engine.downloadModelIfNeeded(conditions).await() }
            }
            (if (Sources.isProject(article.sourceId)) article.title else preserveTerms(article.title) { engine.translate(it).await() }) to preserveTerms(original) { engine.translate(it).await() }
        }
        val date = dateLabel(article.publishedAt)
        val introduction = "来自${article.sourceName}，${if (article.dateEstimated) "收录于" else "更新于"}$date。$title。" +
            if (article.summaryOnly) "本条根据订阅摘要播报。" else ""
        val spoken = NarrationText.clean(introduction + "\n" + Summarizer.extract(body, maxChars = 1100))
        val updated = article.copy(spokenText = spoken, error = "", scriptVersion = NarrationText.VERSION)
        repository.dao.script(article.id, spoken, NarrationText.VERSION)
        return updated
    }
    private suspend fun preserveTerms(text: String, translate: suspend (String) -> String): String {
        // Translate surrounding prose separately, so product names cannot be altered by the model.
        val terms = Regex("(?i)\\b(OpenAI|ChatGPT|GPT-[a-zA-Z0-9.-]+|Anthropic|Claude|Gemini|Hugging Face|GitHub|DeepSeek|Llama|PyTorch|TensorFlow|API|LLM|RAG|LoRA|Transformer|CUDA)\\b")
        val result = StringBuilder()
        var offset = 0
        for (match in terms.findAll(text)) {
            val before = text.substring(offset, match.range.first)
            if (before.isNotBlank()) result.append(translate(before)) else result.append(before)
            result.append(match.value)
            offset = match.range.last + 1
        }
        val tail = text.substring(offset)
        if (tail.isNotBlank()) result.append(translate(tail)) else result.append(tail)
        return result.toString()
    }
    fun close() { translators.values.forEach { it.close() }; languageIdentifier.close() }
}
