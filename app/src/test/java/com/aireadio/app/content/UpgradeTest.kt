package com.aireadio.app.content

import com.aireadio.app.data.*
import com.aireadio.app.playback.PcmWave
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class UpgradeTest {
    private val now = 1800000000000L
    private fun article(id: String, source: String, age: Long = 0, heard: Long = 0) = Article(id, source, source,
        "模型消息 $id", "https://example.com/$id", now - age, now, playCount = if (heard > 0) 1 else 0, lastHeardAt = heard)
    @Test fun domesticUnheardThenOpenaiThenOverseas() {
        val a = listOf(article("us", "google"), article("oa", "openai"), article("cn", "qbitai", 100000))
        assertEquals(listOf("cn", "oa", "us"), ContentRules.ranked(a, RadioSettings(), now).map { it.id })
        assertEquals("oa", ContentRules.next(a, RadioSettings(priority = PriorityMode.OPENAI_FIRST), now)?.id)
        assertEquals("oa", ContentRules.next(a, RadioSettings(), now, setOf("cn"))?.id)
    }
    @Test fun customRegionAndReviewsUseDomesticPriorityButFinishAllUnheardFirst() {
        val prefs = RadioSettings(customFeeds = listOf(FeedSource("custom", "中文", "https://example.com/feed", region = SourceRegion.DOMESTIC)),
            enabledSources = setOf("custom", "openai", "qbitai"))
        val items = listOf(article("a", "custom", heard = now - 100), article("b", "qbitai", heard = now - 500), article("c", "openai"))
        assertEquals("c", ContentRules.next(items, prefs, now)?.id)
        assertEquals("b", ContentRules.next(items, prefs, now, setOf("c"))?.id)
    }
    @Test fun categoriesFavorPracticalContentAndRespectFilter() {
        val tutorial = article("tutorial", "infoq", 500).copy(title = "RAG 部署实战与代码示例")
        val news = article("news", "infoq").copy(title = "大模型新版本发布")
        assertEquals(tutorial.id, ContentRules.next(listOf(news, tutorial), RadioSettings(), now)?.id)
        assertFalse(ContentRules.eligible(news, RadioSettings(categories = setOf(ContentCategory.PRACTICE)), now))
    }
    @Test fun infoqIndexedPublicDataExtractsRealDateAndIgnoresNonAi() {
        val json = """[{"article_title":1,"article_summary":2,"publish_time":3,"uuid":4},"RAG 实战教程","包含代码",1800000000000,"abc",{"article_title":6,"publish_time":3,"uuid":7},"其他行业资讯","def"]"""
        val html = "<script id=\"__NUXT_DATA__\" type=\"application/json\">$json</script>"
        val items = FeedClient.parseInfoq(html, Sources.builtIn.first { it.id == "infoq" }, now)
        assertEquals(1, items.size); assertEquals(now, items.single().publishedAt)
        assertEquals("https://www.infoq.cn/article/abc", items.single().url)
        assertFalse(items.single().dateEstimated)
    }
    @Test fun zhidxMonthDayRolloverAndChangedPageDetection() {
        val date = ContentRules.parseDate("2027-01-02T00:00:00Z")!!
        val html = """<div class="info-left-content"><div class="info-left-title"><a href="https://www.zhidx.com/p/123.html">大模型更新</a></div><div class="info-left-desc">原文摘要</div><div class="ilr-time">12-31</div></div>"""
        val source = Sources.builtIn.first { it.id == "zhidx" }
        assertEquals(ContentRules.parseDate("2026-12-30T16:00:00Z"), FeedClient.parseZhidx(html, source, date).single().publishedAt)
        assertTrue(runCatching { FeedClient.parseZhidx("<p>changed</p>", source, now) }.isFailure)
    }
    @Test fun narrationRemovesNonSpeechContentWithoutInventingDetails() {
        val input = "# 更新\n[原文重点](https://example.com) 使用 API 与 RAG，提升 20%。\n```python\nprint('secret')\n```\n下载大小 12 MB，2026年。"
        val spoken = NarrationText.pronounce(input)
        assertTrue(spoken.contains("原文重点")); assertTrue(spoken.contains("A P I")); assertTrue(spoken.contains("百分之20"))
        assertTrue(spoken.contains("12 兆字节")); assertTrue(spoken.contains("二零二六年"))
        assertFalse(spoken.contains("https")); assertFalse(spoken.contains("secret")); assertFalse(spoken.contains("#"))
        assertEquals("短消息。", NarrationText.chunks("短消息。").single().text)
        assertTrue(NarrationText.chunks("内容".repeat(300)).all { it.text.length <= 180 })
    }
    @Test fun waveWritesValidPcmWithControlledSilence() {
        val file = File.createTempFile("wave-test", ".wav")
        try {
            PcmWave.Writer(file, 24000).use { it.samples(floatArrayOf(0f, .5f, -.5f)); it.silence(200) }
            val pcm = PcmWave.read(file)
            assertEquals(24000, pcm.rate); assertEquals(1, pcm.channels)
            assertEquals(6 + 9600, pcm.bytes.size)
        } finally { file.delete() }
    }
}
