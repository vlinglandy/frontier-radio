package com.aireadio.app.content

import com.aireadio.app.data.*
import org.junit.Assert.*
import org.junit.Test

class FeedClientTest {
    private val source = FeedSource("test", "测试", "https://example.com/feed")
    @Test fun `RSS retains publication date title and HTML content`() {
        val xml = """<rss xmlns:content="http://purl.org/rss/1.0/modules/content/"><channel><item>
            <title>GPT update</title><link>https://example.com/story?utm_source=rss</link>
            <pubDate>Wed, 07 Oct 2026 12:00:00 GMT</pubDate><content:encoded><![CDATA[<p>A useful model update.</p>]]></content:encoded>
            </item></channel></rss>"""
        val article = FeedClient.parseFeed(xml, source, 100).single()
        assertEquals("A useful model update.", article.snippet)
        assertEquals("https://example.com/story", article.url)
        assertFalse(article.dateEstimated)
    }
    @Test fun `Atom selects alternate link and prefers published over updated`() {
        val xml = """<feed xmlns="http://www.w3.org/2005/Atom"><entry><title>Research</title>
            <link rel="self" href="/api/1"/><link rel="alternate" href="/research"/>
            <published>2026-10-01T12:00:00Z</published><updated>2026-10-07T12:00:00Z</updated><summary>A new study</summary></entry></feed>"""
        val article = FeedClient.parseFeed(xml, source, 100).single()
        assertEquals("https://example.com/research", article.url)
        assertEquals(ContentRules.parseDate("2026-10-01T12:00:00Z"), article.publishedAt)
    }
    @Test fun `missing date is visibly recorded as discovery time and unsafe links are skipped`() {
        val xml = """<rss><channel><item><title>Undated</title><link>https://example.com/new</link></item>
            <item><title>Unsafe</title><link>javascript:alert(1)</link></item></channel></rss>"""
        val article = FeedClient.parseFeed(xml, source, 1234).single()
        assertEquals(1234, article.publishedAt)
        assertTrue(article.dateEstimated)
    }
    @Test fun `non-feed and malformed addresses fail independently`() {
        assertTrue(runCatching { FeedClient.parseFeed("<html>Access denied</html>", source, 100) }.isFailure)
        assertTrue(FeedClient.parseFeed("<rss><channel/></rss>", source, 100).isEmpty())
    }
    @Test fun `Anthropic cards expose headline and actual date`() {
        val html = """<main><a href="/news/release"><h2>Claude release</h2><time>Oct 7, 2026</time><p>New capabilities.</p></a></main>"""
        val article = FeedClient.parseAnthropic(html, Sources.builtIn.first { it.id == "anthropic" }, 100).single()
        assertEquals("Claude release", article.title)
        assertFalse(article.dateEstimated)
    }
    @Test fun `GitHub retains real stars and pushed timestamp`() {
        val json = """{"items":[{"full_name":"team/agent","html_url":"https://github.com/team/agent","pushed_at":"2026-10-07T12:00:00Z","stargazers_count":1234,"description":"AI agent tools"}]}"""
        val article = FeedClient.parseGithub(json, Sources.builtIn.first { it.id == "github" }, 100).single()
        assertEquals(1234L, article.heat)
        assertEquals("AI agent tools", article.snippet)
    }
    @Test fun `Hacker News ignores unrelated stories and retains comments`() {
        val json = """{"hits":[{"title":"A chair design","url":"https://example.com/chair","points":100},
            {"title":"New LLM research","url":"https://example.com/llm","created_at":"2026-10-07T12:00:00Z","points":80,"num_comments":42}]}"""
        val article = FeedClient.parseHackerNews(json, Sources.builtIn.first { it.id == "hackernews" }, 100).single()
        assertEquals(80L, article.heat)
        assertEquals(42, article.comments)
    }
}
