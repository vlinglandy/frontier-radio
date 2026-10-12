package com.aireadio.app.content

import org.junit.Assert.*
import org.junit.Test

class SummarizerTest {
    @Test fun `short text stays short and is never padded`() {
        val text = "OpenAI released a model update."
        assertEquals(text, Summarizer.extract(text))
    }
    @Test fun `summary consists of real sentences in original order`() {
        val sentences = (1..40).map { "Research item $it describes neural model training and practical evaluation results." }
        val summary = Summarizer.extract(sentences.joinToString(" "), 600)
        assertTrue(summary.length <= 600)
        val selected = summary.split('\n')
        assertTrue(selected.all { it in sentences })
        assertEquals(selected.map { sentences.indexOf(it) }.sorted(), selected.map { sentences.indexOf(it) })
        assertEquals(sentences.first(), selected.first())
    }
    @Test fun `Chinese sentence boundaries preserve factual extracts`() {
        val sentences = (1..30).map { "第${it}项研究介绍了模型训练中的具体实验结果与实际评估方法。" }
        val summary = Summarizer.extract(sentences.joinToString(""), 200)
        assertTrue(summary.length <= 200)
        assertTrue(summary.split('\n').all { it in sentences })
    }
    @Test fun `article extraction excludes navigation scripts and paywall prompts`() {
        val html = """<html><nav>Many unrelated links and promotions on the website.</nav><main>
            <p>This published research describes the new evaluation technique with measured results.</p>
            <script>Bad content</script><p>Sign in to continue reading the full article today.</p></main></html>"""
        val body = Summarizer.articleBody(html, "https://example.com")
        assertTrue(body.contains("published research"))
        assertFalse(body.contains("unrelated"))
        assertFalse(body.contains("Sign in"))
    }
}
