package com.aireadio.app.content

import com.aireadio.app.data.*
import org.junit.Assert.*
import org.junit.Test

class ContentRulesTest {
    private val now = 1_800_000_000_000L
    private fun article(id: String, source: String = "openai", ageDays: Int = 0, heat: Long? = null, heard: Long = 0) = Article(
        id, source, source, "GPT agent update $id", "https://example.com/$id", now - ageDays * 86_400_000L, now,
        heat = heat, lastHeardAt = heard, playCount = if (heard > 0) 1 else 0)
    @Test fun `tracking links converge without losing semantic parameters`() {
        assertEquals("https://example.com/news?a=1", ContentRules.canonical("https://EXAMPLE.com/news/?a=1&utm_source=x#part"))
        assertNotEquals(ContentRules.canonical("https://example.com/item?id=1"), ContentRules.canonical("https://example.com/item?id=2"))
        assertEquals("https://example.com/search?q=AI%20tools&ref=main", ContentRules.canonical("https://example.com/search?q=AI%20tools&ref=main"))
    }
    @Test fun `reject insecure and credential URLs`() {
        for (url in listOf("http://example.com/feed", "file:///etc/passwd", "https://a:b@example.com/feed")) {
            assertTrue(runCatching { ContentRules.canonical(url) }.isFailure)
        }
    }
    @Test fun `dates accept RSS and Atom timezones`() {
        assertEquals(ContentRules.parseDate("2026-10-07T12:00:00Z"), ContentRules.parseDate("Wed, 07 Oct 2026 12:00:00 GMT"))
        assertEquals(ContentRules.parseDate("2026-10-07T12:00:00Z"), ContentRules.parseDate("Wed, 07 Oct 2026 12:00:00 +0000"))
        assertNotNull(ContentRules.parseDate("Oct 7, 2026"))
        assertNull(ContentRules.parseDate("not a date"))
    }
    @Test fun `filter dates sources keywords and meaningful heat thresholds`() {
        assertFalse(ContentRules.eligible(article("old", ageDays = 8), RadioSettings(), now))
        assertFalse(ContentRules.eligible(article("disabled", source = "google"), RadioSettings(enabledSources = setOf("openai")), now))
        assertTrue(ContentRules.eligible(article("match"), RadioSettings(keywords = "多模态，Agent"), now))
        assertFalse(ContentRules.eligible(article("other"), RadioSettings(keywords = "视觉"), now))
        val prefs = RadioSettings(minStars = 100, minCommunityScore = 30)
        assertFalse(ContentRules.eligible(article("repo", "github", heat = 99), prefs, now))
        assertTrue(ContentRules.eligible(article("official"), prefs, now))
        assertFalse(ContentRules.eligible(article("story", "hackernews", heat = 29), prefs, now))
    }
    @Test fun `unheard OpenAI has priority then chronological content`() {
        val all = listOf(article("new", "google"), article("oa", ageDays = 2), article("stale", ageDays = 10))
        assertEquals(listOf("oa", "new"), ContentRules.ranked(all, RadioSettings(), now).map { it.id })
    }
    @Test fun `heat ranks within each source rather than comparing stars with votes`() {
        val all = listOf(article("popular-hn", "hackernews", heat = 300), article("quiet-hn", "hackernews", heat = 30),
            article("popular-gh", "github", ageDays = 1, heat = 50_000), article("quiet-gh", "github", heat = 2000))
        val ranked = ContentRules.ranked(all, RadioSettings(sort = SortMode.HEAT), now)
        assertEquals(setOf("popular-hn", "popular-gh"), ranked.take(2).map { it.id }.toSet())
    }
    @Test fun `finish unheard content before revisiting least recently heard`() {
        val all = listOf(article("recent-review", heard = now - 100), article("old-review", "google", heard = now - 1000), article("unheard", "github"))
        assertEquals("unheard", ContentRules.next(all, RadioSettings(), now)?.id)
        assertEquals("recent-review", ContentRules.next(all, RadioSettings(), now, setOf("unheard"))?.id)
        assertNull(ContentRules.next(all, RadioSettings(), now, all.map { it.id }.toSet()))
    }
    @Test fun `one item can be reviewed while respecting configured dates`() {
        assertEquals("one", ContentRules.next(listOf(article("one", heard = now - 100)), RadioSettings(), now)?.id)
        assertNull(ContentRules.next(listOf(article("one", ageDays = 8, heard = now - 100)), RadioSettings(), now))
    }
    @Test fun `failed unheard item cannot prevent playable item from entering a new cycle`() {
        val all = listOf(article("bad"), article("good", heard = now - 10))
        val consumed = mutableSetOf("good")
        assertEquals("good", ContentRules.nextInCycle(all, RadioSettings(), now, consumed, setOf("bad"))?.id)
        assertTrue(consumed.isEmpty())
        assertNull(ContentRules.nextInCycle(all, RadioSettings(), now, consumed, setOf("bad", "good")))
    }
}
