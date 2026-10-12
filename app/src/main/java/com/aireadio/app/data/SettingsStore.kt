package com.aireadio.app.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

private val Context.radioPreferences by preferencesDataStore("radio_settings")
class SettingsStore(private val context: Context) {
    private val settingsKey = stringPreferencesKey("settings")
    private val currentKey = stringPreferencesKey("current_article")
    val settings: Flow<RadioSettings> = context.radioPreferences.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }.map { decode(it[settingsKey]) }
    suspend fun current() = settings.first()
    suspend fun currentId() = context.radioPreferences.data.first()[currentKey].orEmpty()
    suspend fun setCurrent(id: String) { context.radioPreferences.edit { it[currentKey] = id } }
    suspend fun save(s: RadioSettings) {
        val json = JSONObject().put("days", s.days).put("start", s.startDate).put("end", s.endDate)
            .put("enabled", JSONArray(s.enabledSources.toList())).put("keywords", s.keywords)
            .put("sort", s.sort.name).put("speed", s.speed).put("refresh", s.refreshMinutes)
            .put("network", s.network.name).put("stars", s.minStars).put("score", s.minCommunityScore)
            .put("priority", s.priority.name).put("practiceFirst", s.practiceFirst)
            .put("categories", JSONArray(s.categories.map { it.name }))
            .put("voiceBackend", s.voiceBackend.name).put("speaker", s.speakerId).put("sourcesVersion", 2)
        json.put("custom", JSONArray().apply { s.customFeeds.forEach {
            put(JSONObject().put("id", it.id).put("name", it.name).put("url", it.url).put("region", it.region.name))
        } })
        context.radioPreferences.edit { it[settingsKey] = json.toString() }
    }
    private fun decode(raw: String?): RadioSettings = runCatching {
        if (raw == null) return RadioSettings()
        val j = JSONObject(raw)
        val enabled = j.optJSONArray("enabled")
        val feeds = j.optJSONArray("custom") ?: JSONArray()
        RadioSettings(
            days = j.optInt("days", 7).coerceIn(1, 365),
            startDate = if (j.has("start") && !j.isNull("start")) j.getLong("start") else null,
            endDate = if (j.has("end") && !j.isNull("end")) j.getLong("end") else null,
            enabledSources = enabled?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() +
                if (j.optInt("sourcesVersion", 1) < 2) Sources.builtIn.filter { it.region == SourceRegion.DOMESTIC }.map { it.id } else emptyList() }
                ?: Sources.builtIn.map { it.id }.toSet(),
            customFeeds = (0 until feeds.length()).map { val f = feeds.getJSONObject(it)
                FeedSource(f.getString("id"), f.getString("name"), f.getString("url"), region = SourceRegion.valueOf(f.optString("region", "OVERSEAS"))) },
            keywords = j.optString("keywords"), sort = SortMode.valueOf(j.optString("sort", "RECENT")),
            speed = j.optDouble("speed", 1.0).toFloat().coerceIn(.6f, 1.8f),
            refreshMinutes = j.optInt("refresh", 15).coerceIn(15, 240),
            network = NetworkPolicy.valueOf(j.optString("network", "STANDARD")),
            minStars = j.optInt("stars").coerceAtLeast(0), minCommunityScore = j.optInt("score").coerceAtLeast(0),
            priority = PriorityMode.valueOf(j.optString("priority", "DOMESTIC_FIRST")),
            practiceFirst = j.optBoolean("practiceFirst", true),
            categories = j.optJSONArray("categories")?.let { a -> (0 until a.length()).map { ContentCategory.valueOf(a.getString(it)) }.toSet() } ?: ContentCategory.entries.toSet(),
            voiceBackend = VoiceBackend.valueOf(j.optString("voiceBackend", "SYSTEM")),
            speakerId = j.optInt("speaker", 3).takeIf { it in 3..102 } ?: 3
        )
    }.getOrDefault(RadioSettings())
}
