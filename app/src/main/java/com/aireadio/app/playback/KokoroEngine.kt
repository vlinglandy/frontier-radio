package com.aireadio.app.playback

import android.content.Context
import com.aireadio.app.content.NetworkGate
import com.aireadio.app.content.NarrationText
import com.aireadio.app.data.NetworkPolicy
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class ModelDownloadState(val ready: Boolean = false, val downloading: Boolean = false,
    val bytes: Long = 0, val total: Long = KokoroEngine.MODEL_BYTES, val message: String = "可下载中文自然音色包，约 140 MB")

class KokoroEngine(context: Context) {
    private val memory = context.getSystemService(android.app.ActivityManager::class.java)
    companion object {
        const val MODEL_BYTES = 147031220L
        const val SHA256 = "a1e94694776049035c4f2c6529f003aaece993c76aae9a78995831c3c4dcafc6"
        const val URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-multi-lang-v1_1.tar.bz2"
    }
    private val base = File(context.filesDir, "narration_models").apply { mkdirs() }
    val directory = File(base, "kokoro-int8-multi-lang-v1_1")
    private val mutex = Mutex()
    private var engine: OfflineTts? = null
    private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS).build()
    val state = MutableStateFlow(ModelDownloadState(ready = ready(), message = if (ready()) "中文自然音色包已准备" else "可下载中文自然音色包，约 140 MB"))
    fun ready() = runCatching { File(directory, ".verified").let { it.isFile && it.readText() == SHA256 } && File(directory, "model.int8.onnx").isFile }.getOrDefault(false)

    suspend fun download(gate: NetworkGate, policy: NetworkPolicy) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (ready()) return@withLock
            check(gate.mayDownloadModel(policy)) { "请连接 Wi-Fi 下载音色包，或配置允许所有网络下载" }
            val archive = File(base, "kokoro.download")
            val staging = File(base, "staging").apply { deleteRecursively(); mkdirs() }
            state.value = ModelDownloadState(downloading = true, message = "正在下载中文自然音色包…")
            val call = client.newCall(Request.Builder().url(URL).build())
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                call.execute().use { response ->
                    check(response.isSuccessful && response.request.url.isHttps) { "音色包下载失败：HTTP ${response.code}" }
                    val body = response.body ?: error("音色包为空")
                    var bytes = 0L
                    body.byteStream().use { input -> archive.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            ensureActive()
                            check(gate.mayDownloadModel(policy)) { "网络不符合下载策略，请连接 Wi-Fi 后重试" }
                            val n = input.read(buffer); if (n < 0) break
                            bytes += n; check(bytes <= MODEL_BYTES) { "音色包大小不匹配" }
                            digest.update(buffer, 0, n); output.write(buffer, 0, n)
                            state.value = state.value.copy(bytes = bytes)
                        }
                    } }
                    check(bytes == MODEL_BYTES) { "音色包下载不完整，请重试" }
                }
                check(digest.digest().joinToString("") { "%02x".format(it) } == SHA256) { "音色包校验失败，请重试" }
                state.value = state.value.copy(message = "下载完成，正在安装音色包…")
                TarArchiveInputStream(BZip2CompressorInputStream(archive.inputStream().buffered())).use { tar ->
                    var total = 0L
                    var count = 0
                    while (true) {
                        ensureActive()
                        val entry = tar.nextTarEntry ?: break
                        check(++count <= 6000 && !entry.isSymbolicLink && !entry.isLink) { "音色包包含不支持的文件" }
                        val file = File(staging, entry.name).canonicalFile
                        check(file.path.startsWith(staging.canonicalPath + File.separator)) { "音色包路径无效" }
                        if (entry.isDirectory) { file.mkdirs(); continue }
                        check(entry.isFile && entry.size in 0..700_000_000L) { "音色包文件无效" }
                        total += entry.size; check(total <= 700_000_000L) { "音色包解压后过大" }
                        file.parentFile?.mkdirs()
                        file.outputStream().use { out ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) { ensureActive(); val n = tar.read(buffer); if (n < 0) break; out.write(buffer, 0, n) }
                        }
                    }
                }
                val extracted = File(staging, directory.name)
                check(File(extracted, "model.int8.onnx").isFile && File(extracted, "voices.bin").isFile && File(extracted, "tokens.txt").isFile) { "音色包缺少必要文件" }
                File(extracted, ".verified").writeText(SHA256)
                directory.deleteRecursively(); check(extracted.renameTo(directory)) { "无法安装音色包" }
                state.value = ModelDownloadState(ready = true, bytes = MODEL_BYTES, message = "中文自然音色包已准备，可以试听")
            } catch (cancel: CancellationException) {
                call.cancel(); state.value = ModelDownloadState(ready = ready(), message = "音色包下载已取消，可重试"); throw cancel
            } catch (error: Exception) {
                state.value = ModelDownloadState(ready = ready(), message = error.message ?: "下载失败，请重试"); throw error
            } finally { archive.delete(); staging.deleteRecursively() }
        }
    }
    suspend fun delete() = withContext(Dispatchers.IO) { mutex.withLock {
        engine?.release(); engine = null
        directory.deleteRecursively()
        state.value = ModelDownloadState(message = "音色包已删除，使用系统中文语音")
    } }
    suspend fun synthesize(text: String, speaker: Int, speed: Float, output: File) = withContext(Dispatchers.IO) { mutex.withLock {
        check(ready()) { "请先下载中文自然音色包" }
        ensureActive()
        if (engine == null) {
            val available = android.app.ActivityManager.MemoryInfo().also(memory::getMemoryInfo)
            check(!available.lowMemory && available.availMem > 384L * 1024 * 1024) { "可用内存不足，请使用系统中文语音" }
        }
        val tts = engine ?: OfflineTts(config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(kokoro = OfflineTtsKokoroModelConfig(
                model = File(directory, "model.int8.onnx").path, voices = File(directory, "voices.bin").path,
                tokens = File(directory, "tokens.txt").path, dataDir = File(directory, "espeak-ng-data").path,
                lexicon = listOf("lexicon-us-en.txt", "lexicon-zh.txt").map { File(directory, it).path }.joinToString(",")),
                numThreads = 2, debug = false),
            ruleFsts = listOf("date-zh.fst", "number-zh.fst").map { File(directory, it) }.filter { it.exists() }.joinToString(",") { it.path }
        )).also { engine = it }
        check(speaker in 3 until tts.numSpeakers()) { "此音色不可用，请重新选择" }
        PcmWave.Writer(output, tts.sampleRate()).use { wave ->
            for (chunk in NarrationText.chunks(text)) {
                ensureActive()
                val job = currentCoroutineContext().job
                val audio = tts.generateWithCallback(chunk.text, speaker, speed) { if (job.isActive) 1 else 0 }
                ensureActive()
                check(audio.samples.isNotEmpty()) { "自然语音生成失败" }
                wave.samples(audio.samples)
                wave.silence(chunk.pauseMs)
            }
        }
    } }
}
