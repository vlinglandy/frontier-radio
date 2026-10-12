package com.aireadio.app.content

import com.aireadio.app.data.*
import java.net.URI
import java.security.MessageDigest
import java.time.*
import java.time.format.DateTimeFormatter

object ContentRules {
    fun category(article: Article): ContentCategory {
        val text = "${article.title} ${article.snippet.take(1800)}"
        return when {
            Sources.isProject(article.sourceId) || Regex("(?i)(教程|实战|实践|部署|示例|代码|开发指南|从零|tutorial|how.to|walkthrough|implementation)").containsMatchIn(text) -> ContentCategory.PRACTICE
            Regex("(?i)(论文|技术报告|训练|推理|评测|架构|原理|research|technical.report|benchmark|architecture|training)").containsMatchIn(text) -> ContentCategory.TECHNICAL
            else -> ContentCategory.NEWS
        }
    }
    fun sourceTier(article: Article, settings: RadioSettings): Int {
        val domestic = (Sources.builtIn + settings.customFeeds).firstOrNull { it.id == article.sourceId }?.region == SourceRegion.DOMESTIC
        return if (settings.priority == PriorityMode.DOMESTIC_FIRST) when {
            domestic -> 0; article.sourceId == "openai" -> 1; else -> 2
        } else when {
            article.sourceId == "openai" -> 0; domestic -> 1; else -> 2
        }
    }
    fun hash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        .joinToString("") { "%02x".format(it) }
    fun canonical(url: String): String {
        val uri = URI(url.trim())
        require(uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.userInfo == null) { "只支持有效的 HTTPS 地址" }
        val query = uri.rawQuery?.split("&")?.filterNot {
            val key = it.substringBefore("=").lowercase()
            key.startsWith("utm_") || key in setOf("fbclid", "gclid")
        }?.joinToString("&")?.ifEmpty { null }
        val port = if (uri.port == -1 || uri.port == 443) "" else ":${uri.port}"
        val path = uri.rawPath.orEmpty().trimEnd('/').ifEmpty { "/" }
        return URI("https://${uri.host.lowercase()}$port$path${query?.let { "?$it" }.orEmpty()}").toASCIIString()
    }
    fun parseDate(raw: String): Long? {
        val date = raw.trim()
        if (date.isEmpty()) return null
        return runCatching { Instant.parse(date).toEpochMilli() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(date).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { ZonedDateTime.parse(date, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { LocalDate.parse(date.take(10)).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrNull()
            ?: listOf("MMM d, yyyy", "MMMM d, yyyy", "MMM dd, yyyy").firstNotNullOfOrNull { pattern ->
                runCatching { LocalDate.parse(date, DateTimeFormatter.ofPattern(pattern, java.util.Locale.US))
                    .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrNull()
            }
    }
    fun eligible(article: Article, settings: RadioSettings, now: Long): Boolean {
        if (article.sourceId !in settings.enabledSources || article.publishedAt !in settings.from(now)..settings.until(now)) return false
        if (category(article) !in settings.categories) return false
        if (Sources.isProject(article.sourceId) && (article.heat ?: 0) < settings.minStars) return false
        if (article.sourceId == "hackernews" && (article.heat ?: 0) < settings.minCommunityScore) return false
        val tokens = settings.tokens()
        return tokens.isEmpty() || tokens.any { token ->
            "${article.title} ${article.snippet} ${article.spokenText}".contains(token, ignoreCase = true)
        }
    }
    fun ranked(articles: List<Article>, settings: RadioSettings, now: Long): List<Article> {
        val eligible = articles.filter { eligible(it, settings, now) }
        val percentiles = eligible.filter { it.heat != null }.groupBy { it.sourceId }.values.flatMap { group ->
            val ordered = group.sortedBy { it.heat }
            ordered.map { article ->
                article.id to (ordered.count { (it.heat ?: 0) <= (article.heat ?: 0) }.toDouble() / ordered.size)
            }
        }.toMap()
        return eligible.sortedWith(compareBy<Article> { sourceTier(it, settings) }
            .thenByDescending { if (settings.sort == SortMode.HEAT) percentiles[it.id] ?: .5 else 0.0 }
            .thenBy { if (settings.practiceFirst) category(it).ordinal else 0 }
            .thenByDescending { it.publishedAt })
    }
    fun next(articles: List<Article>, settings: RadioSettings, now: Long, excluded: Set<String> = emptySet()): Article? {
        val ranked = ranked(articles, settings, now).filter { it.id !in excluded }
        return ranked.firstOrNull { it.playCount == 0 }
            ?: ranked.minWithOrNull(compareBy<Article> { sourceTier(it, settings) }.thenBy { it.lastHeardAt }.thenByDescending { it.publishedAt })
    }
    fun nextInCycle(articles: List<Article>, settings: RadioSettings, now: Long,
                    consumed: MutableSet<String>, unavailable: Set<String> = emptySet()): Article? {
        next(articles, settings, now, consumed + unavailable)?.let { return it }
        val recycled = next(articles, settings, now, unavailable)
        if (recycled != null) consumed.clear()
        return recycled
    }
}
