package com.aireadio.app.ui

import android.app.DatePickerDialog
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aireadio.app.content.ContentRules
import com.aireadio.app.data.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Calendar
import kotlin.math.round

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable fun SettingsSheet(initial: RadioSettings, statuses: List<SourceStatus>, model: RadioViewModel, onDismiss: () -> Unit, onSave: (RadioSettings) -> Unit) {
    var draft by remember(initial) { mutableStateOf(initial) }
    var feedUrl by remember { mutableStateOf("") }
    var feedName by remember { mutableStateOf("") }
    var validation by remember { mutableStateOf("") }
    var feedRegion by remember { mutableStateOf(SourceRegion.DOMESTIC) }
    val download by model.voiceDownload.collectAsStateWithLifecycle()
    val previewing by model.previewing.collectAsStateWithLifecycle()
    val voice by model.voice.collectAsStateWithLifecycle()
    val context = LocalContext.current
    fun pickDate(start: Boolean) {
        val calendar = Calendar.getInstance().apply { timeInMillis = (if (start) draft.startDate else draft.endDate) ?: System.currentTimeMillis() }
        DatePickerDialog(context, { _, y, m, day ->
            val millis = LocalDate.of(y, m + 1, day).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            draft = if (start) draft.copy(startDate = millis) else draft.copy(endDate = millis)
        }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)).show()
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Surface) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("信息配置", fontSize = 25.sp, fontWeight = FontWeight.Bold)
            Text("挑选值得听的内容，让电台适合你的节奏。", color = Muted, fontSize = 12.sp)
            SectionLabel("时间范围")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1, 7, 30).forEach { days -> FilterChip(selected = draft.days == days && draft.startDate == null && draft.endDate == null,
                    onClick = { draft = draft.copy(days = days, startDate = null, endDate = null) }, label = { Text(if (days == 1) "最近 24 小时" else "最近 $days 天") }) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pickDate(true) }, modifier = Modifier.weight(1f)) { Text(draft.startDate?.let(::calendarDate) ?: "开始日期") }
                OutlinedButton(onClick = { pickDate(false) }, modifier = Modifier.weight(1f)) { Text(draft.endDate?.let(::calendarDate) ?: "结束日期") }
            }
            SectionLabel("内容排序")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = draft.priority == PriorityMode.DOMESTIC_FIRST, onClick = { draft = draft.copy(priority = PriorityMode.DOMESTIC_FIRST) }, label = { Text("国内优先") })
                FilterChip(selected = draft.priority == PriorityMode.OPENAI_FIRST, onClick = { draft = draft.copy(priority = PriorityMode.OPENAI_FIRST) }, label = { Text("OpenAI 优先") })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = draft.sort == SortMode.RECENT, onClick = { draft = draft.copy(sort = SortMode.RECENT) }, label = { Text("最新优先") })
                FilterChip(selected = draft.sort == SortMode.HEAT, onClick = { draft = draft.copy(sort = SortMode.HEAT) }, label = { Text("热度优先") })
            }
            Text("国内优先时，先听国内未听内容，再听 OpenAI 和其他海外内容；手动点选可直接播放任意文章。热度按来源内部排名。", fontSize = 11.sp, color = Muted)
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("优先实操与技术细节", modifier = Modifier.weight(1f))
                Switch(checked = draft.practiceFirst, onCheckedChange = { draft = draft.copy(practiceFirst = it) })
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(ContentCategory.PRACTICE to "实操", ContentCategory.TECHNICAL to "技术解读", ContentCategory.NEWS to "新闻").forEach { (category, label) ->
                    FilterChip(selected = category in draft.categories, onClick = { draft = draft.copy(categories = if (category in draft.categories) draft.categories - category else draft.categories + category) }, label = { Text(label) })
                }
            }
            OutlinedTextField(value = draft.keywords, onValueChange = { draft = draft.copy(keywords = it) }, label = { Text("关键词，可用逗号分隔") }, placeholder = { Text("例如：GPT, Agent, 多模态") }, supportingText = { Text("留空收听全部；填写后匹配任一关键词。") }, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = if (draft.minStars == 0) "" else draft.minStars.toString(), onValueChange = { value ->
                    if (value.all(Char::isDigit)) draft = draft.copy(minStars = value.toIntOrNull()?.coerceIn(0, 1_000_000) ?: 0)
                }, label = { Text("最低 GitHub 星标") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(value = if (draft.minCommunityScore == 0) "" else draft.minCommunityScore.toString(), onValueChange = { value ->
                    if (value.all(Char::isDigit)) draft = draft.copy(minCommunityScore = value.toIntOrNull()?.coerceIn(0, 100_000) ?: 0)
                }, label = { Text("最低社区评分") }, singleLine = true, modifier = Modifier.weight(1f))
            }
            SectionLabel("订阅来源")
            (Sources.builtIn + draft.customFeeds).forEach { source ->
                val status = statuses.firstOrNull { it.id == source.id }
                Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(source.name, fontSize = 15.sp)
                        Text(if (source.region == SourceRegion.DOMESTIC) "国内来源" else "海外来源", color = Mint, fontSize = 10.sp)
                        Text(status?.let { if (it.failures > 0) "更新失败：${it.message}" else "${it.message} · ${dateLabel(it.lastSuccessAt)}" } ?: "尚未更新", color = if ((status?.failures ?: 0) > 0) MaterialTheme.colorScheme.error else Muted, fontSize = 10.sp, maxLines = 2)
                    }
                    if (source in draft.customFeeds) IconButton(onClick = { draft = draft.copy(customFeeds = draft.customFeeds.filterNot { it.id == source.id }, enabledSources = draft.enabledSources - source.id) }) { Icon(Icons.Default.DeleteOutline, "删除 ${source.name}", tint = Muted) }
                    Switch(checked = source.id in draft.enabledSources, onCheckedChange = { enabled ->
                        draft = draft.copy(enabledSources = if (enabled) draft.enabledSources + source.id else draft.enabledSources - source.id)
                    })
                }
                if (draft.customFeeds.any { it.id == source.id }) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SourceRegion.entries.forEach { region ->
                        FilterChip(selected = source.region == region, onClick = { draft = draft.copy(customFeeds = draft.customFeeds.map { if (it.id == source.id) it.copy(region = region) else it }) }, label = { Text(if (region == SourceRegion.DOMESTIC) "国内" else "海外") })
                    }
                }
            }
            Text("候选来源（尚未启用自动订阅）：\n" + Sources.candidates.joinToString("\n"), color = Muted, fontSize = 11.sp)
            OutlinedTextField(value = feedName, onValueChange = { feedName = it }, label = { Text("自定义订阅名称（可选）") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(value = feedUrl, onValueChange = { feedUrl = it; validation = "" }, label = { Text("RSS / Atom 的 HTTPS 地址") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SourceRegion.entries.forEach { region -> FilterChip(selected = feedRegion == region, onClick = { feedRegion = region }, label = { Text(if (region == SourceRegion.DOMESTIC) "国内订阅" else "海外订阅") }) }
            }
            OutlinedButton(onClick = {
                runCatching {
                    val url = feedUrl.trim()
                    val canonical = ContentRules.canonical(url)
                    require((Sources.builtIn + draft.customFeeds).none { runCatching { ContentRules.canonical(it.url) }.getOrNull() == canonical }) { "此订阅已存在" }
                    val source = FeedSource("custom-${ContentRules.hash(canonical).take(12)}", feedName.trim().ifEmpty { java.net.URI(url).host }, url, region = feedRegion)
                    draft = draft.copy(customFeeds = draft.customFeeds + source, enabledSources = draft.enabledSources + source.id)
                    feedUrl = ""; feedName = ""; validation = ""
                }.onFailure { validation = it.message ?: "订阅地址无效" }
            }, enabled = feedUrl.isNotBlank()) { Text("添加订阅") }
            SectionLabel("语音与更新")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = draft.voiceBackend == VoiceBackend.SYSTEM, onClick = { draft = draft.copy(voiceBackend = VoiceBackend.SYSTEM) }, label = { Text("系统中文语音") })
                FilterChip(selected = draft.voiceBackend == VoiceBackend.KOKORO, onClick = { draft = draft.copy(voiceBackend = VoiceBackend.KOKORO) }, label = { Text("自然离线音色") })
            }
            if (draft.voiceBackend == VoiceBackend.KOKORO) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(3 to "中文女声", 58 to "中文男声").forEach { (id, label) -> FilterChip(selected = draft.speakerId == id, onClick = { draft = draft.copy(speakerId = id) }, label = { Text(label) }) }
            }
            Text(download.message, color = Muted, fontSize = 12.sp)
            if (download.downloading) {
                LinearProgressIndicator(progress = { (download.bytes.toFloat() / download.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Text("${download.bytes / 1024 / 1024} / ${download.total / 1024 / 1024} MB", color = Muted, fontSize = 11.sp)
                TextButton(onClick = model::cancelVoiceDownload) { Text("取消下载") }
            } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!download.ready) OutlinedButton(onClick = { model.downloadVoice(draft.network) }) { Text("下载 / 重试音色包") }
                else TextButton(onClick = { draft = draft.copy(voiceBackend = VoiceBackend.SYSTEM); model.deleteVoice() }) { Text("删除音色包") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { if (previewing) model.stopPreview() else model.previewVoice(draft) }, enabled = draft.voiceBackend != VoiceBackend.KOKORO || download.ready) { Text(if (previewing) "停止试听" else "试听当前音色") }
            }
            Text(voice.message, color = Muted, fontSize = 11.sp)
            Text("自然音色需单独下载；不可用时自动使用系统中文语音。试听会暂停电台，音色设置保存后在下一篇生效。", fontSize = 11.sp, color = Muted)
            Text("语速  ${draft.speed}×", color = Mint)
            Slider(value = draft.speed, onValueChange = { draft = draft.copy(speed = round(it * 10) / 10) }, valueRange = .6f..1.8f, steps = 11)
            Text("语速在下一条生效。新内容听完后自动刷新，再轮换复习。", fontSize = 11.sp, color = Muted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(15, 30, 60).forEach { minutes -> FilterChip(selected = draft.refreshMinutes == minutes, onClick = { draft = draft.copy(refreshMinutes = minutes) }, label = { Text("每 $minutes 分钟") }) }
            }
            SectionLabel("网络策略")
            listOf(NetworkPolicy.STANDARD to "随时刷新，Wi-Fi 下载模型与离线准备", NetworkPolicy.WIFI_ONLY to "所有下载只使用 Wi-Fi", NetworkPolicy.ALL to "所有网络都可下载").forEach { (policy, label) ->
                Row(Modifier.fillMaxWidth().clickable { draft = draft.copy(network = policy) }, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    RadioButton(selected = draft.network == policy, onClick = { draft = draft.copy(network = policy) })
                    Text(label, fontSize = 13.sp)
                }
            }
            Text("后台更新由安卓系统调度；播放期间会主动检查更新。离线音频缓存最多 200 MB。", color = Muted, fontSize = 11.sp)
            if (validation.isNotBlank()) Text(validation, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            Button(onClick = {
                if (draft.categories.isEmpty()) validation = "请至少选择一种内容分类"
                else if (draft.startDate != null && draft.endDate != null && draft.startDate!! > draft.endDate!!) validation = "开始日期不能晚于结束日期"
                else if (draft.endDate != null && draft.startDate == null) validation = "自定义结束日期时请同时选择开始日期"
                else onSave(draft)
            }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("保存配置") }
        }
    }
}
@Composable private fun SectionLabel(text: String) { Text(text, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp)) }
private fun calendarDate(ms: Long) = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(ms))
