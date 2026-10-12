package com.aireadio.app.content

import com.aireadio.app.data.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.json.JSONArray
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit

class HttpFailure(val status: Int, val retryAt: Long, message: String) : IOException(message)
class FeedClient(private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS).callTimeout(35, TimeUnit.SECONDS).build()) {
    fun get(url: String): String {
        ContentRules.canonical(url)
        val request = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 AIFrontierRadio/1.1 (personal RSS reader)")
            .header("Accept", "application/rss+xml, application/atom+xml, application/json, text/html, */*").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val retry = response.header("Retry-After")?.let { value ->
                    value.toLongOrNull()?.let { System.currentTimeMillis() + it * 1000 }
                        ?: ContentRules.parseDate(value)
                } ?: 0
                throw HttpFailure(response.code, retry, "HTTP ${response.code}：${response.message}")
            }
            require(response.request.url.isHttps) { "来源重定向到非 HTTPS 地址" }
            val body = response.body ?: throw IOException("来源返回空内容")
            body.byteStream().use { stream ->
                val buffer = ByteArray(8192)
                val output = java.io.ByteArrayOutputStream()
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    if (output.size() > 4 * 1024 * 1024) throw IOException("单次内容超过 4 MB")
                }
                val bytes = output.toByteArray()
                return String(bytes, body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8)
            }
        }
    }
    fun fetch(source: FeedSource, settings: RadioSettings, now: Long): List<Article> = when (source.kind) {
        SourceKind.RSS -> parseFeed(get(source.url), source, now).let { articles -> if (source.id == "qbitai") articles.filter(::aiRelevant) else articles }
        SourceKind.ANTHROPIC -> parseAnthropic(get(source.url), source, now)
        SourceKind.INFOQ -> parseInfoq(get(source.url), source, now)
        SourceKind.ZHIDX -> parseZhidx(get(source.url), source, now)
        SourceKind.GITHUB_ORG -> parseGithubOrg(get("${source.url}?sort=pushed&per_page=30"), source, now)
        SourceKind.GITHUB -> {
            val date = Instant.ofEpochMilli(settings.from(now)).toString().take(10)
            val query = java.net.URLEncoder.encode("topic:artificial-intelligence pushed:>=$date archived:false", "UTF-8")
            val llm = java.net.URLEncoder.encode("topic:llm pushed:>=$date archived:false", "UTF-8")
            (parseGithub(get("${source.url}/search/repositories?q=$query&sort=stars&order=desc&per_page=30"), source, now)
                + parseGithub(get("${source.url}/search/repositories?q=$llm&sort=updated&order=desc&per_page=20"), source, now)).distinctBy { it.id }
        }
        SourceKind.HACKER_NEWS -> {
            val cutoff = settings.from(now) / 1000
            parseHackerNews(get("${source.url}/search_by_date?query=AI&tags=story&numericFilters=created_at_i%3E$cutoff&hitsPerPage=50"), source, now)
        }
    }
    companion object {
        private val aiTopic = Regex("(?i)(人工智能|大模型|智能体|机器学习|深度学习|多模态|提示词|\\b(ai|llm|gpt|agent|rag|openai|claude|gemini|deepseek|qwen|codex|copilot|transformer|diffusion)\\b)")
        fun aiRelevant(article: Article) = aiTopic.containsMatchIn("${article.title} ${article.snippet}")
        fun parseInfoq(html: String, source: FeedSource, now: Long): List<Article> {
            val doc = Jsoup.parse(html, source.url)
            val data = doc.selectFirst("script#__NUXT_DATA__")?.data()?.let { runCatching { JSONArray(it) }.getOrNull() }
            val result = mutableListOf<Article>()
            if (data != null) for (i in 0 until data.length()) {
                val node = data.optJSONObject(i) ?: continue
                if (!node.has("uuid") || !node.has("publish_time")) continue
                fun field(key: String): String = node.optInt(key, -1).takeIf { it >= 0 }?.let { data.opt(it)?.toString() }.orEmpty()
                val title = field("article_title").ifEmpty { field("title") }
                val summary = field("article_summary").ifEmpty { field("summary") }
                val uuid = field("uuid")
                if (uuid.isBlank() || !aiTopic.containsMatchIn("$title $summary")) continue
                val rawDate = field("publish_time")
                val date = rawDate.toLongOrNull()?.let { if (it < 100_000_000_000L) it * 1000 else it }
                    ?: ContentRules.parseDate(rawDate)
                item(source, title, "https://www.infoq.cn/article/$uuid", date, now, summary)?.let(result::add)
            }
            require(result.isNotEmpty()) { "InfoQ 页面未返回可识别的 AI 文章，稍后重试" }
            return result.distinctBy { it.id }.take(40)
        }
        fun parseZhidx(html: String, source: FeedSource, now: Long): List<Article> {
            val doc = Jsoup.parse(html, source.url)
            val today = Instant.ofEpochMilli(now).atZone(java.time.ZoneId.of("Asia/Shanghai")).toLocalDate()
            val result = doc.select(".info-left-content").mapNotNull { card ->
                val link = card.selectFirst(".info-left-title a[href]") ?: return@mapNotNull null
                val title = link.attr("title").ifBlank { link.text() }
                val summary = card.selectFirst(".info-left-desc")?.text().orEmpty()
                val time = card.selectFirst(".ilr-time")?.text().orEmpty()
                val date = ContentRules.parseDate(time) ?: runCatching {
                    val md = java.time.MonthDay.parse(time, java.time.format.DateTimeFormatter.ofPattern("MM-dd"))
                    var day = md.atYear(today.year)
                    if (day.isAfter(today)) day = md.atYear(today.year - 1)
                    day.atStartOfDay(java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()
                }.getOrNull()
                item(source, title, link.absUrl("href"), date, now, summary)
            }.distinctBy { it.id }.take(40)
            require(result.isNotEmpty()) { "智东西页面结构发生变化，未找到文章" }
            return result
        }
        fun parseGithubOrg(json: String, source: FeedSource, now: Long): List<Article> =
            parseGithub(JSONObject().put("items", JSONArray(json)).toString(), source, now)
        private fun item(source: FeedSource, title: String, url: String, date: Long?, now: Long,
                         snippet: String, heat: Long? = null, comments: Int? = null): Article? = runCatching {
            val canonical = ContentRules.canonical(url)
            val clean = Jsoup.parse(snippet).text().take(16_000)
            if (title.isBlank()) return null
            Article(ContentRules.hash(canonical), source.id, source.name, Jsoup.parse(title).text().take(300), canonical,
                date ?: now, now, dateEstimated = date == null, snippet = clean,
                contentHash = ContentRules.hash("${title.lowercase().trim()}|${clean.lowercase().take(1200)}"), heat = heat, comments = comments)
        }.getOrNull()
        private fun Element.direct(vararg names: String): String = children().firstOrNull { child ->
            names.any { it.equals(child.tagName(), ignoreCase = true) }
        }?.text().orEmpty()

        fun parseFeed(xml: String, source: FeedSource, now: Long): List<Article> {
            val doc = Jsoup.parse(xml, source.url, Parser.xmlParser())
            val elements = doc.getElementsByTag("item").ifEmpty { doc.getElementsByTag("entry") }
            require(elements.isNotEmpty() || doc.getElementsByTag("rss").isNotEmpty() || doc.getElementsByTag("feed").isNotEmpty()) { "地址未返回 RSS / Atom" }
            return elements.take(80).mapNotNull { entry ->
                val links = entry.children().filter { it.tagName().equals("link", true) }
                val link = links.firstOrNull { it.attr("rel") in listOf("", "alternate") }
                val url = if (link?.hasAttr("href") == true) link.absUrl("href").ifEmpty { link.attr("href") }
                    else link?.text().orEmpty().ifEmpty { entry.direct("guid") }
                val dates = listOf("pubDate", "published", "dc:date", "updated")
                val date = dates.firstNotNullOfOrNull { ContentRules.parseDate(entry.direct(it)) }
                val body = entry.direct("content:encoded").ifEmpty { entry.direct("content") }
                    .ifEmpty { entry.direct("description", "summary") }
                item(source, entry.direct("title"), url, date, now, body)
            }
        }
        fun parseAnthropic(html: String, source: FeedSource, now: Long): List<Article> {
            val doc = Jsoup.parse(html, source.url)
            return doc.select("a[href^=/news/]").mapNotNull { link ->
                val card = generateSequence(link as Element?) { it.parent() }.take(4)
                    .firstOrNull { it.selectFirst("time") != null } ?: link
                val time = card.selectFirst("time")
                val date = time?.let { ContentRules.parseDate(it.attr("datetime").ifEmpty { it.text() }) }
                val title = link.selectFirst("h2, h3, h4")?.text()
                    ?: link.selectFirst("[class*=title]")?.text() ?: link.text()
                item(source, title, link.absUrl("href"), date, now, card.selectFirst("p")?.text().orEmpty())
            }.distinctBy { it.id }.take(60)
        }
        fun parseGithub(json: String, source: FeedSource, now: Long): List<Article> {
            val items = JSONObject(json).optJSONArray("items") ?: return emptyList()
            return (0 until items.length()).mapNotNull { index ->
                val repo = items.getJSONObject(index)
                item(source, repo.getString("full_name"), repo.getString("html_url"),
                    ContentRules.parseDate(repo.optString("pushed_at")), now,
                    repo.optString("description").takeUnless { it == "null" }.orEmpty(), repo.optLong("stargazers_count"))
            }
        }
        fun parseHackerNews(json: String, source: FeedSource, now: Long): List<Article> {
            val hits = JSONObject(json).optJSONArray("hits") ?: return emptyList()
            val ai = Regex("(?i)\\b(ai|llm|gpt|openai|anthropic|claude|gemini|deepseek|transformer|diffusion|machine learning|neural|artificial intelligence)\\b")
            return (0 until hits.length()).mapNotNull { index ->
                val hit = hits.getJSONObject(index)
                val title = hit.optString("title")
                if (!ai.containsMatchIn(title)) return@mapNotNull null
                val url = hit.optString("url").takeUnless { it.isBlank() || it == "null" }
                    ?: "https://news.ycombinator.com/item?id=${hit.optString("objectID")}" 
                item(source, title, url, ContentRules.parseDate(hit.optString("created_at")), now,
                    hit.optString("story_text").takeUnless { it == "null" }.orEmpty(), hit.optLong("points"), hit.optInt("num_comments"))
            }
        }
    }
}
