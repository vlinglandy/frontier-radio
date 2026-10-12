package com.aireadio.app.content

/** Deterministic speech formatting; never adds claims to the source text. */
object NarrationText {
    const val VERSION = 2
    fun clean(text: String): String = text
        .replace(Regex("(?s)```.*?```"), "\n")
        .replace(Regex("!\\[[^]]*]\\([^)]*\\)"), "")
        .replace(Regex("\\[([^]]+)]\\([^)]*\\)"), "$1")
        .replace(Regex("https?://\\S+"), "")
        .replace(Regex("(?m)^\\s*[#>*-]+\\s*"), "")
        .replace(Regex("[`*_]"), "")
        .replace(Regex("[\\p{So}\\p{Cs}]"), "")
        .replace(Regex("[ \\t]+"), " ")
        .replace(Regex("\\n{3,}"), "\n\n").trim()

    fun pronounce(text: String): String {
        var result = clean(text)
        val names = mapOf("API" to "A P I", "LLM" to "大语言模型", "RAG" to "检索增强生成", "GPU" to "G P U", "CPU" to "C P U", "LoRA" to "洛拉")
        for ((name, spoken) in names) result = result.replace(Regex("(?i)\\b${Regex.escape(name)}\\b"), spoken)
        result = result.replace(Regex("(\\d+(?:\\.\\d+)?)%"), "百分之$1")
            .replace(Regex("(?i)(\\d+(?:\\.\\d+)?)\\s*GB\\b"), "$1 吉字节")
            .replace(Regex("(?i)(\\d+(?:\\.\\d+)?)\\s*MB\\b"), "$1 兆字节")
            .replace(Regex("\\b(20\\d{2})年")) { m -> m.groupValues[1].map { "零一二三四五六七八九"[it.digitToInt()] }.joinToString("") + "年" }
        return result
    }
    data class Chunk(val text: String, val pauseMs: Int)
    fun chunks(text: String): List<Chunk> {
        val chunks = mutableListOf<Chunk>()
        for (paragraph in pronounce(text).lines().filter { it.isNotBlank() }) {
            val sentences = Regex("[^。！？!?；;]+[。！？!?；;]?").findAll(paragraph).map { it.value.trim() }.filter { it.isNotBlank() }.toList()
            for ((i, sentence) in sentences.withIndex()) {
                sentence.chunked(180).forEachIndexed { j, part ->
                    chunks += Chunk(part, if (i == sentences.lastIndex && j == (sentence.length - 1) / 180) 450 else 200)
                }
            }
        }
        return chunks
    }
}
