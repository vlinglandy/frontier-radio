package com.aireadio.app.content

import org.jsoup.Jsoup
import kotlin.math.ln

object Summarizer {
    private val stopWords = setOf("the", "and", "for", "that", "this", "with", "from", "are", "was", "will", "you", "your", "our", "can", "have", "has", "not", "but", "its", "into", "more", "about", "their", "they", "also", "been")
    fun extract(text: String, maxChars: Int = 3500): String {
        val clean = text.replace(Regex("[\\t ]+"), " ").trim()
        if (clean.length <= maxChars) return clean
        val sentences = clean.split(Regex("(?<=[.!?])\\s+|(?<=[。！？])|[\\r\\n]+"))
            .map { it.trim() }.filter { it.length >= 25 }.distinct()
        if (sentences.isEmpty()) return clean.take(maxChars).substringBeforeLast(' ', clean.take(maxChars))
        val tokens = sentences.map { sentence -> Regex("[a-zA-Z][a-zA-Z0-9-]{2,}").findAll(sentence)
            .map { it.value.lowercase() }.filter { it !in stopWords }.toList() }
        val frequency = tokens.flatten().groupingBy { it }.eachCount()
        val ranked = sentences.indices.sortedByDescending { index ->
            tokens[index].distinct().sumOf { ln(1.0 + (frequency[it] ?: 0)) } / kotlin.math.sqrt(tokens[index].size.coerceAtLeast(1).toDouble()) + if (index < 2) 4.0 else 0.0
        }
        val selected = mutableSetOf<Int>()
        var size = 0
        // Always retain the beginning to give the later excerpts context.
        for (index in (listOf(0) + ranked).distinct()) {
            if (size + sentences[index].length <= maxChars) { selected += index; size += sentences[index].length + 1 }
        }
        return selected.sorted().joinToString("\n") { sentences[it] }
            .ifEmpty { sentences.first().take(maxChars) }
    }
    fun articleBody(html: String, url: String): String {
        val doc = Jsoup.parse(html, url)
        doc.select("script, style, nav, header, footer, aside, form, button, svg, pre, code, [role=navigation], [aria-hidden=true]").remove()
        val candidates = doc.select("article, main, [role=main], .post-content, .article-content, .entry-content")
        val main = candidates.maxByOrNull { it.select("p").sumOf { p -> p.text().length } } ?: return ""
        return main.select("h1, h2, h3, p, li").map { it.text().trim() }
            .filter { it.length > 25 && !it.contains(Regex("(?i)accept all cookies|subscribe to our newsletter|sign in to continue")) }
            .distinct().joinToString("\n").take(60_000)
    }
}
