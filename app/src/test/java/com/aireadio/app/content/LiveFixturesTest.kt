package com.aireadio.app.content

import com.aireadio.app.data.Sources
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Optional contract check against downloads made by tools/verify_sources.py. */
class LiveFixturesTest {
    @Test fun `current public source payloads still match adapters`() {
        val root = File(System.getProperty("liveFixtures", "").orEmpty())
        assumeTrue(File(root, "openai.xml").exists())
        for (source in Sources.builtIn) {
            val path = File(root, when (source.id) {
                "anthropic" -> "anthropic.html"
                "github" -> "github.json"
                "hackernews" -> "hackernews.json"
                "infoq", "zhidx" -> "${source.id}.html"
                "qwen", "deepseek" -> "${source.id}.json"
                else -> "${source.id}.xml"
            })
            assertTrue("${source.name} fixture missing", path.exists())
            val raw = path.readText()
            val items = when (source.id) {
                "anthropic" -> FeedClient.parseAnthropic(raw, source, System.currentTimeMillis())
                "github" -> FeedClient.parseGithub(raw, source, System.currentTimeMillis())
                "hackernews" -> FeedClient.parseHackerNews(raw, source, System.currentTimeMillis())
                "infoq" -> FeedClient.parseInfoq(raw, source, System.currentTimeMillis())
                "zhidx" -> FeedClient.parseZhidx(raw, source, System.currentTimeMillis())
                "qwen", "deepseek" -> FeedClient.parseGithubOrg(raw, source, System.currentTimeMillis())
                "qbitai" -> FeedClient.parseFeed(raw, source, System.currentTimeMillis()).filter(FeedClient::aiRelevant)
                else -> FeedClient.parseFeed(raw, source, System.currentTimeMillis())
            }
            assertTrue("${source.name} adapter produced no articles", items.isNotEmpty())
            assertTrue("${source.name} has no dated articles", items.any { !it.dateEstimated })
            assertTrue(items.all { it.url.startsWith("https://") })
            println("${source.name}: ${items.size} articles, ${items.count { !it.dateEstimated }} dated; first: ${items.first().title}")
        }
    }
}
