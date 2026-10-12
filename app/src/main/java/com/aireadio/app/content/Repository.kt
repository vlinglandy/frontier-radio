package com.aireadio.app.content

import com.aireadio.app.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class Repository(val dao: RadioDao, val settings: SettingsStore, val gate: NetworkGate, val client: FeedClient) {
    val refreshing = MutableStateFlow(false)
    val refreshMessage = MutableStateFlow("")
    private val lock = Mutex()
    suspend fun refresh(force: Boolean = false) = lock.withLock {
        val prefs = settings.current()
        if (!gate.mayRefresh(prefs.network)) {
            refreshMessage.value = if (!gate.online()) "当前离线，继续播放缓存" else "已设置仅 Wi-Fi 更新"
            return@withLock
        }
        refreshing.value = true
        var added = 0
        var succeeded = 0
        try {
            // Serial requests keep the public interfaces within their anonymous quotas.
            for (source in prefs.sources()) {
                val now = System.currentTimeMillis()
                val status = dao.sourceStatus(source.id)
                if (status != null && status.retryAfter > now) continue
                if (!force && status != null && now - status.checkedAt < prefs.refreshMinutes * 60_000L) continue
                try {
                    val items = withContext(Dispatchers.IO) { client.fetch(source, prefs, now) }
                    for (article in items) {
                        val previous = dao.article(article.id)
                        if (previous != null) {
                            dao.metadata(previous.id, article.heat, article.comments,
                                if (article.dateEstimated) previous.publishedAt else article.publishedAt,
                                previous.dateEstimated && article.dateEstimated)
                        } else if (dao.byHash(article.contentHash) == null) {
                            if (dao.insert(article) != -1L) added++
                        }
                    }
                    dao.sourceStatus(SourceStatus(source.id, source.name, now, now, message = "获取 ${items.size} 条"))
                    succeeded++
                } catch (cancel: CancellationException) { throw cancel }
                catch (error: Exception) {
                    val failures = (status?.failures ?: 0) + 1
                    val backoff = (15L * (1L shl (failures - 1).coerceAtMost(6))).coerceAtMost(360) * 60_000
                    dao.sourceStatus(SourceStatus(source.id, source.name, now, status?.lastSuccessAt ?: 0,
                        maxOf(now + backoff, (error as? HttpFailure)?.retryAt ?: 0), failures,
                        error.message.orEmpty().take(250)))
                }
            }
            refreshMessage.value = "新增 $added 条 · $succeeded 个来源更新成功"
            dao.prune(System.currentTimeMillis() - 365L * 86_400_000, settings.currentId())
        } finally { refreshing.value = false }
    }
    suspend fun enrich(article: Article): Article {
        if (article.body.isNotBlank() || article.summaryOnly) return article
        if (!gate.mayRefresh(settings.current().network)) {
            val fallback = article.copy(body = article.snippet, summaryOnly = true)
            dao.body(article.id, fallback.body, true)
            return fallback
        }
        val body = try {
            withContext(Dispatchers.IO) {
                if (Sources.isProject(article.sourceId)) {
                    val path = java.net.URI(article.url).path.trim('/')
                    var readme = ""
                    for (branch in listOf("main", "master")) {
                        try { readme = client.get("https://raw.githubusercontent.com/$path/$branch/README.md"); break }
                        catch (error: HttpFailure) { if (error.status != 404) throw error }
                    }
                    readme.replace(Regex("!\\[[^]]*]\\([^)]*\\)"), "")
                        .replace(Regex("\\[([^]]+)]\\([^)]*\\)"), "$1")
                        .replace(Regex("(?s)```.*?```"), "").take(60_000)
                } else Summarizer.articleBody(client.get(article.url), article.url)
            }
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { "" }
        val usable = body.length >= 150
        val result = article.copy(body = if (usable) body else article.snippet, summaryOnly = !usable)
        dao.body(result.id, result.body, result.summaryOnly)
        return result
    }
}
