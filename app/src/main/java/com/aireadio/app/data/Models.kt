package com.aireadio.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class SourceKind { RSS, ANTHROPIC, GITHUB, GITHUB_ORG, HACKER_NEWS, INFOQ, ZHIDX }
enum class SourceRegion { DOMESTIC, OVERSEAS }
enum class PriorityMode { DOMESTIC_FIRST, OPENAI_FIRST }
enum class ContentCategory { PRACTICE, TECHNICAL, NEWS }
enum class VoiceBackend { SYSTEM, KOKORO }
data class FeedSource(val id: String, val name: String, val url: String, val kind: SourceKind = SourceKind.RSS,
    val region: SourceRegion = SourceRegion.OVERSEAS)
object Sources {
    val builtIn = listOf(
        FeedSource("qbitai", "量子位", "https://www.qbitai.com/feed", region = SourceRegion.DOMESTIC),
        FeedSource("infoq", "InfoQ 中文", "https://www.infoq.cn/", SourceKind.INFOQ, SourceRegion.DOMESTIC),
        FeedSource("zhidx", "智东西", "https://www.zhidx.com/p/category/人工智能", SourceKind.ZHIDX, SourceRegion.DOMESTIC),
        FeedSource("qwen", "Qwen 官方项目", "https://api.github.com/orgs/QwenLM/repos", SourceKind.GITHUB_ORG, SourceRegion.DOMESTIC),
        FeedSource("deepseek", "DeepSeek 官方项目", "https://api.github.com/orgs/deepseek-ai/repos", SourceKind.GITHUB_ORG, SourceRegion.DOMESTIC),
        FeedSource("openai", "OpenAI", "https://openai.com/news/rss.xml"),
        FeedSource("google", "Google AI", "https://blog.google/technology/ai/rss/"),
        FeedSource("anthropic", "Anthropic", "https://www.anthropic.com/news", SourceKind.ANTHROPIC),
        FeedSource("huggingface", "Hugging Face", "https://huggingface.co/blog/feed.xml"),
        FeedSource("github", "GitHub", "https://api.github.com", SourceKind.GITHUB),
        FeedSource("hackernews", "Hacker News", "https://hn.algolia.com/api/v1", SourceKind.HACKER_NEWS)
    )
    val candidates = listOf("掘金 — https://juejin.cn/", "机器之心 — https://www.jiqizhixin.com/", "智源社区 — https://hub.baai.ac.cn/")
    fun isProject(id: String) = builtIn.any { it.id == id && it.kind in setOf(SourceKind.GITHUB, SourceKind.GITHUB_ORG) }
}
enum class SortMode { RECENT, HEAT }
enum class NetworkPolicy { STANDARD, WIFI_ONLY, ALL }
data class RadioSettings(
    val days: Int = 7,
    val startDate: Long? = null,
    val endDate: Long? = null,
    val enabledSources: Set<String> = Sources.builtIn.map { it.id }.toSet(),
    val customFeeds: List<FeedSource> = emptyList(),
    val keywords: String = "",
    val sort: SortMode = SortMode.RECENT,
    val speed: Float = 1f,
    val refreshMinutes: Int = 15,
    val network: NetworkPolicy = NetworkPolicy.STANDARD,
    val minStars: Int = 0,
    val minCommunityScore: Int = 0,
    val priority: PriorityMode = PriorityMode.DOMESTIC_FIRST,
    val categories: Set<ContentCategory> = ContentCategory.entries.toSet(),
    val practiceFirst: Boolean = true,
    val voiceBackend: VoiceBackend = VoiceBackend.SYSTEM,
    val speakerId: Int = 3
) {
    fun sources() = (Sources.builtIn + customFeeds).filter { it.id in enabledSources }
    fun from(now: Long) = startDate ?: now - days * 86_400_000L
    fun until(now: Long) = endDate?.plus(86_400_000L - 1) ?: now
    fun tokens() = keywords.split(Regex("[,，;；\\n]+" )).map { it.trim() }.filter { it.isNotEmpty() }
}

@Entity(tableName = "articles")
data class Article(
    @PrimaryKey val id: String,
    val sourceId: String,
    val sourceName: String,
    val title: String,
    val url: String,
    val publishedAt: Long,
    val discoveredAt: Long,
    val dateEstimated: Boolean = false,
    val snippet: String = "",
    val contentHash: String = "",
    val heat: Long? = null,
    val comments: Int? = null,
    val body: String = "",
    val spokenText: String = "",
    val summaryOnly: Boolean = false,
    val lastHeardAt: Long = 0,
    val playCount: Int = 0,
    val positionMs: Long = 0,
    val error: String = "",
    @androidx.room.ColumnInfo(defaultValue = "0") val scriptVersion: Int = 0
)
@Entity(tableName = "source_status")
data class SourceStatus(
    @PrimaryKey val id: String,
    val name: String,
    val checkedAt: Long,
    val lastSuccessAt: Long = 0,
    val retryAfter: Long = 0,
    val failures: Int = 0,
    val message: String = ""
)
fun dateLabel(timestamp: Long): String = DateTimeFormatter.ofPattern("MM月dd日 HH:mm")
    .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(timestamp))
