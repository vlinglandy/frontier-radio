# AI 前沿电台

Android 8.0 及以上的个人 AI 知识电台。配置日期、订阅和关键词后，一键持续收听中文重点；默认先听国内内容，再听 OpenAI 和其他海外内容；可以直接点击任何文章播放，播完自动续播。

## 安装与第一次播放

1. 将 `dist/前沿电台.apk` 传到 arm64 Android 手机并安装。发布包仅包含 arm64-v8a；当前使用本机调试证书签名，首次安装需允许文件管理器安装应用。
2. 中文原文可直接播报。收听英文内容时，连接 Wi-Fi 并点击「准备免费翻译」，或在播报准备时自动下载所需免费翻译模型。
3. 默认使用系统中文离线 TTS。也可打开信息配置，下载约 140 MB 的 Kokoro 自然音色包，选择「自然离线音色」、中文男女声并试听，再保存。音色包解压约 205 MB，不计入音频缓存；没有可用自然音色时自动回退系统语音。
4. 点击刷新获取文章；点击文章卡片即可从头播放该篇，播完自动继续。大按钮控制电台播放／暂停；主界面及媒体通知提供重播本条。文章卡片另有「原文／重点」入口。

不需要账号、服务器、API Key 或付费模型。内容整理是抽取原文重点和离线机器翻译，质量取决于原文、翻译模型与语音引擎，并非云端大模型深度讲解。长内容控制在约 1100 个中文字符以内，通常约 2–5 分钟；短消息不补写内容。

如果语音包已安装但仍提示缺失，请在语音设置中试播中文，再返回；必要时重新打开应用，让系统语音引擎重新加载。

## 配置与播放规则

- 时间：默认最近 7 天，可选 24 小时、30 天或自定义日期。无日期内容明确标记为「收录」时间。
- 来源：新增量子位、InfoQ 中文、智东西、Qwen 与 DeepSeek 官方项目，保留 OpenAI、Google AI、Anthropic、Hugging Face、GitHub、Hacker News，共 11 个来源。自定义 HTTPS RSS / Atom 可设置国内／海外。掘金、机器之心、智源社区目前仅列为候选，尚未启用自动获取。
- 排序：默认国内未听 → OpenAI 未听 → 其他海外未听 → 刷新 → 复习，可切换 OpenAI 优先。默认优先实操与技术细节，支持分类开关；热度模式先比较来源内部真实热度，再比较内容类型和时间。GitHub 使用星标，Hacker News 使用评分和评论数，不虚构官方文章热度。
- 关键词：逗号分隔，匹配任意一项；留空不限制。支持最低 GitHub 星标与最低社区评分。
- 手动点选可直接切换到任何文章；连续点选以最后一次为准，准备失败显示重试入口。重播从零开始，不重复累计历史条目的已听次数。新内容优先；已听完时刷新后按来源优先级和组内最久未听顺序复习。复习也遵守日期、来源和关键词。复习播报明确标记。
- 播放时按配置每 15 / 30 / 60 分钟检查更新；未播放时使用 WorkManager，系统可能延后执行。
- 默认移动网络允许获取文章，较大模型与额外内容预备仅限 Wi-Fi。支持所有下载仅 Wi-Fi或允许所有网络。
- 保存收听记录与播放位置。主动停止、强制关闭或系统回收后不自动开始播放；再点播放恢复。
- 当前与后续两条提前合成音频，受网络策略限制。音频缓存目标上限 200 MB，当前和预备中的音频受保护，其余优先清理已听旧文件。

安卓各厂商的电池限制可能影响长时间播放。没有符合条件的内容时应用等待刷新；持续静默的前台服务可能被系统收回，需再次点击播放。来电、音频焦点丢失和耳机断开会暂停。

## 本地构建

要求 JDK 17 或 21、Android SDK Platform 35、网络访问 Google Maven 和 Maven Central。工程包含固定版本 sherpa-onnx 1.13.8 AAR（SHA-256：633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96），无需另行下载安卓库。

在 Android Studio 打开项目并同步，或配置 `ANDROID_HOME`。也可新建本机的 `local.properties`：

```properties
sdk.dir=/your/path/to/Android/sdk
```

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。构建缓存若有损坏，可以使用独立目录：

```sh
./gradlew --gradle-user-home /tmp/ai-radio-gradle :app:assembleDebug
```

## 验证与来源维护

```sh
python3 tools/verify_sources.py
./gradlew :app:testDebugUnitTest -PliveFixtures="$PWD/build/source-fixtures"
# 设备测试需中文系统 TTS；Gradle 可能清除应用数据，先准备独立音色夹具。
python3 tools/prepare_voice_fixture.py
./gradlew :app:connectedDebugAndroidTest
```

来源定义在 `app/src/main/java/com/aireadio/app/data/Models.kt`；网页和公开接口适配在 `content/FeedClient.kt`。来源故障在配置页显示，限流按 Retry-After 和指数退避重试。一个来源失败不阻止其他来源更新。

GitHub 搜索按 AI / LLM topic 与最近 pushed 日期发现项目；Hacker News 使用公开 Algolia 搜索，进一步过滤 AI 关键词。正文读取不绕过登录或付费限制；无法提取时回退到订阅摘要。网页结构变更可能需要更新适配器。

所有来源和模型下载地址见 `docs/SOURCES.md`。第三方授权见 `docs/licenses/`。完整验收记录见 `docs/VALIDATION.md`。来源内容版权归原作者，应用保留原文链接，供个人学习使用。
