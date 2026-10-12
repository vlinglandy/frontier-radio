package com.aireadio.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aireadio.app.content.ContentRules
import com.aireadio.app.R
import com.aireadio.app.data.*
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

val Background = Color(0xFF0B1423)
val Surface = Color(0xFF152236)
val Mint = Color(0xFF6AE4C5)
val Muted = Color(0xFF94A6BE)
val Cyan = Color(0xFF4FC3F7)
val Ink = Color(0xFFEAF1FA)
private val Hairline = Color.White.copy(alpha = .07f)
@Composable fun RadioTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Mint, onPrimary = Background,
        background = Background, onBackground = Ink, surface = Surface, onSurface = Ink, secondary = Color(0xFF90BFFF)), content = content)
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun RadioScreen(model: RadioViewModel, onPlay: () -> Unit, onVoiceSettings: () -> Unit, onArticlePlay: (String) -> Unit = model::playArticle) {
    val prefs by model.settings.collectAsStateWithLifecycle()
    val articles by model.articles.collectAsStateWithLifecycle()
    val statuses by model.sourceStatus.collectAsStateWithLifecycle()
    val play by model.playback.collectAsStateWithLifecycle()
    val preparation by model.preparation.collectAsStateWithLifecycle()
    val voice by model.voice.collectAsStateWithLifecycle()
    val refreshing by model.refreshing.collectAsStateWithLifecycle()
    val refreshMessage by model.refreshMessage.collectAsStateWithLifecycle()
    val error by model.error.collectAsStateWithLifecycle()
    val starting by model.starting.collectAsStateWithLifecycle()
    var settingsOpen by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Article?>(null) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(error) { if (error.isNotBlank()) { snackbar.showSnackbar(error); model.error.value = "" } }
    val context = LocalContext.current
    val ranked = ContentRules.ranked(articles, prefs, System.currentTimeMillis())
    val motion = rememberInfiniteTransition(label = "motion")
    val phase by motion.animateFloat(0f, 2 * PI.toFloat(), infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "phase")
    val breath by motion.animateFloat(0f, 1f, infiniteRepeatable(tween(1600), RepeatMode.Reverse), label = "breath")
    Scaffold(containerColor = Color.Transparent, contentColor = Ink, modifier = Modifier.background(Background).drawBehind {
        drawRect(Brush.radialGradient(listOf(Mint.copy(alpha = .13f), Color.Transparent), Offset(0f, 0f), size.width * .9f))
        drawRect(Brush.radialGradient(listOf(Color(0xFF7C8CFF).copy(alpha = .10f), Color.Transparent), Offset(size.width, size.height * .18f), size.width * .8f))
    }, snackbarHost = { SnackbarHost(snackbar) }) { insets ->
        LazyColumn(contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = insets.calculateTopPadding() + 12.dp, bottom = insets.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.fillMaxSize()) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.padding(end = 14.dp).size(48.dp).clip(RoundedCornerShape(14.dp)).border(1.dp, Hairline, RoundedCornerShape(14.dp))) {
                        Image(painterResource(R.drawable.ic_launcher_background), null, Modifier.fillMaxSize())
                        Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.fillMaxSize().scale(1.5f))
                    }
                    Column(Modifier.weight(1f)) {
                        Text("FRONTIER / RADIO", color = Mint, fontSize = 11.sp, letterSpacing = 3.sp, fontWeight = FontWeight.Bold)
                        Text("AI 前沿电台", color = Ink, fontSize = 27.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                    }
                    IconButton(onClick = { settingsOpen = true }) { Icon(Icons.Default.Tune, "信息配置", tint = Mint) }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Tag(if (prefs.startDate != null) "自定义日期" else "最近 ${prefs.days} 天")
                    Tag(if (prefs.priority == PriorityMode.DOMESTIC_FIRST) "国内优先" else "OpenAI 优先")
                    Tag("免费 · 中文")
                }
            }
            item {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF1E4450), Color(0xFF1A2E4A), Surface)))
                    .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = .14f), Hairline)), RoundedCornerShape(28.dp)).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(if (play.active) Mint else Muted))
                        Text(if (play.review) "  REVIEW / 复习" else "  NOW / 当前内容", color = Mint, fontSize = 11.sp, letterSpacing = 2.sp)
                        Spacer(Modifier.weight(1f)); Text("${prefs.speed}×", color = Muted, fontSize = 12.sp)
                    }
                    Text(play.article?.title ?: "让前沿知识，\n成为你的日常背景音", color = Ink, fontSize = 24.sp,
                        lineHeight = 34.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 12.dp), maxLines = 4, overflow = TextOverflow.Ellipsis)
                    Text(play.article?.let { "${it.sourceName} · ${if (it.dateEstimated) "收录 " else ""}${dateLabel(it.publishedAt)}" }
                        ?: "订阅可靠来源 · 自动整理 · 持续收听", color = Muted, fontSize = 12.sp, modifier = Modifier.fillMaxWidth())
                    Row(Modifier.height(64.dp).padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                        listOf(12, 22, 34, 18, 42, 28, 46, 23, 35, 16, 28, 12).forEachIndexed { index, height ->
                            val wave = if (play.playing) .45f + .55f * abs(sin(phase + index * .7f)) else 1f
                            Box(Modifier.width(5.dp).height((height * wave).dp).clip(RoundedCornerShape(4.dp)).background(Brush.verticalGradient(listOf(Mint, Cyan)), alpha = if (play.playing) .9f else .25f + (index % 3) * .08f))
                        }
                    }
                    Box(Modifier.padding(top = 10.dp).size(128.dp), contentAlignment = Alignment.Center) {
                        val glow = if (play.playing) breath else 0f
                        Box(Modifier.size((100 + 28 * glow).dp).clip(CircleShape).background(Brush.radialGradient(listOf(Mint.copy(alpha = .35f * (1 - glow * .6f)), Color.Transparent))))
                        Button(onClick = onPlay, modifier = Modifier.size(92.dp).background(Brush.linearGradient(listOf(Color(0xFF9AF5DE), Mint, Cyan)), CircleShape), shape = CircleShape,
                            contentPadding = PaddingValues(0.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = Background)) {
                            Icon(if (play.active || starting) Icons.Default.Pause else Icons.Default.PlayArrow, if (play.active || starting) "暂停" else "播放", modifier = Modifier.size(44.dp))
                        }
                    }
                    Text(if (starting) "正在准备离线中文能力…" else if (play.preparing) "正在整理与合成…" else if (play.active) "持续播放中" else "点击开始收听", fontSize = 13.sp, color = Mint, modifier = Modifier.padding(top = 14.dp))
                    Text(play.message, color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
                    if (prefs.voiceBackend == VoiceBackend.KOKORO && voice.message.contains("系统")) Text(voice.message, color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 5.dp))
                    play.retryArticleId?.let { id -> TextButton(onClick = { onArticlePlay(id) }) { Text("重试播放这篇") } }
                    if (play.duration > 0) {
                        LinearProgressIndicator(progress = { (play.position.toFloat() / play.duration).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 16.dp), color = Mint, trackColor = Background)
                        Text("${clock(play.position)} / ${clock(play.duration)}", fontSize = 11.sp, color = Muted, modifier = Modifier.padding(top = 5.dp))
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                        TextButton(onClick = model::stop, enabled = play.active || play.article != null) { Icon(Icons.Default.Stop, null, Modifier.size(18.dp)); Text("停止") }
                        TextButton(onClick = model::next, enabled = play.active && !play.preparing) { Text("下一条"); Icon(Icons.Default.SkipNext, null, Modifier.size(20.dp)) }
                        TextButton(onClick = model::replay, enabled = play.canReplay && !play.preparing) { Text("重播") }
                        TextButton(onClick = { play.article?.let { selected = it } }, enabled = play.article != null) { Text("原文 / 重点") }
                    }
                }
            }
            if (!preparation.translationReady || !voice.ready || preparation.preparing) item {
                Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Surface), shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("首次收听准备", fontWeight = FontWeight.SemiBold)
                        Text(preparation.message, fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 7.dp))
                        if (!voice.ready) Text(voice.message, fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 5.dp))
                        if (preparation.preparing) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
                        Row {
                            TextButton(onClick = model::prepare, enabled = !preparation.preparing) { Text("准备免费翻译") }
                            TextButton(onClick = onVoiceSettings) { Text("中文语音设置") }
                        }
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("点选文章收听", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Text("${ranked.count { it.playCount == 0 }} 条未听 · ${ranked.size} 条符合配置", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp))
                    }
                    IconButton(onClick = model::refresh, enabled = !refreshing) {
                        if (refreshing) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) else Icon(Icons.Default.Refresh, "刷新", tint = Mint)
                    }
                }
                if (refreshMessage.isNotBlank()) Text(refreshMessage, color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
            }
            if (ranked.isEmpty()) item {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Surface).padding(22.dp)) {
                    Text("你的下一条发现，从这里开始", color = Ink, fontWeight = FontWeight.Medium)
                    Text("点击刷新或播放获取订阅；也可以在配置中扩大日期范围、添加 RSS。", color = Muted, fontSize = 13.sp, lineHeight = 22.sp, modifier = Modifier.padding(top = 9.dp))
                }
            }
            items(ranked, key = { it.id }) { article ->
                val isCurrent = play.article?.id == article.id
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (isCurrent) Color(0xFF224C51) else Surface)
                    .border(1.dp, if (isCurrent) Mint.copy(alpha = .35f) else Hairline, RoundedCornerShape(16.dp))
                    .drawBehind { if (isCurrent) drawRect(Brush.verticalGradient(listOf(Mint, Cyan)), size = Size(3.dp.toPx(), size.height)) }
                    .clickable { onArticlePlay(article.id) }.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(article.sourceName, color = if (article.sourceId == "openai") Mint else Color(0xFF90BFFF), fontSize = 11.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.weight(1f)); Text(if (isCurrent) "当前播放" else if (article.playCount > 0) "已听 ${article.playCount} 次" else "未听", color = if (isCurrent) Mint else Muted, fontSize = 10.sp)
                    }
                    Text(article.title, color = Ink, fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 9.dp))
                    Text("${if (article.dateEstimated) "收录 " else ""}${dateLabel(article.publishedAt)}" +
                        (article.heat?.let { if (Sources.isProject(article.sourceId)) " · ★ $it" else " · 评分 $it" } ?: "") +
                        (article.comments?.let { " · $it 评论" } ?: ""), color = Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 8.dp))
                    if (article.error.isNotBlank()) Text(article.error, color = MaterialTheme.colorScheme.error, fontSize = 11.sp, maxLines = 2, modifier = Modifier.padding(top = 5.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("点击即可播放", color = Mint, fontSize = 11.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { selected = article }) { Text("原文 / 重点", fontSize = 12.sp) }
                    }
                }
            }
        }
    }
    if (settingsOpen) SettingsSheet(prefs, statuses, model, onDismiss = { model.stopPreview(); settingsOpen = false }, onSave = { model.stopPreview(); model.save(it); settingsOpen = false })
    selected?.let { article ->
        ModalBottomSheet(onDismissRequest = { selected = null }, containerColor = Surface) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(22.dp)) {
                Text(article.sourceName, color = Mint, fontSize = 12.sp)
                Text(article.title, color = Ink, fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 12.dp))
                Text(if (article.summaryOnly) "订阅摘要" else "原文重点", color = Muted, fontSize = 12.sp)
                Text(article.spokenText.ifBlank { article.snippet.ifBlank { "播放时自动提取正文并准备中文重点。" } }, lineHeight = 25.sp, modifier = Modifier.padding(top = 10.dp))
                Button(onClick = { selected = null; onArticlePlay(article.id) }, modifier = Modifier.padding(top = 16.dp).fillMaxWidth()) { Text("播放这篇文章") }
                Button(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(article.url))) } }, modifier = Modifier.padding(vertical = 20.dp)) {
                    Text("打开原文"); Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.padding(start = 8.dp).size(16.dp))
                }
            }
        }
    }
}
@Composable private fun Tag(text: String) {
    Text(text, color = Muted, fontSize = 10.sp, modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Surface.copy(alpha = .7f)).border(1.dp, Hairline, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 7.dp))
}
private fun clock(ms: Long) = "%d:%02d".format(ms / 60_000, ms / 1000 % 60)
