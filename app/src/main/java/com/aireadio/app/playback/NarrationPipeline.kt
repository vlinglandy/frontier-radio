package com.aireadio.app.playback

import com.aireadio.app.content.ChinesePreparer
import com.aireadio.app.data.Article
import java.io.File
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

data class Narration(val article: Article, val file: File)

/** The service controls sequencing; this component prepares playable audio. */
fun interface NarrationPipeline {
    suspend fun prepare(article: Article, review: Boolean, speed: Float): Narration
}

class OfflineNarrationPipeline(private val preparer: ChinesePreparer, private val speech: SpeechEngine) : NarrationPipeline {
    override suspend fun prepare(article: Article, review: Boolean, speed: Float): Narration {
        try {
            return withTimeout(360_000) {
                val scripted = preparer.script(article)
                Narration(scripted, speech.synthesize(scripted, review, speed))
            }
        } catch (timeout: TimeoutCancellationException) {
            throw IllegalStateException("此条内容准备超时，稍后重试", timeout)
        }
    }
}
