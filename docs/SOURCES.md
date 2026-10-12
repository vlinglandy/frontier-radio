# 信息来源与语音模型

内置来源全部默认启用；默认国内优先，可改为 OpenAI 优先。以下链接经过 2026-10-12 联网取得样本并验证解析。可访问和样本验证不保证未来永不改版；失败原因会显示在配置页。

| 来源 | 类型 | 获取地址 |
| --- | --- | --- |
| 量子位 | 国内 RSS，仅保留 AI 相关标题／摘要 | https://www.qbitai.com/feed |
| InfoQ 中文 | 国内公开网页，提取页面已包含的文章数据并筛选 AI | https://www.infoq.cn/ |
| 智东西 | 国内人工智能分类公开网页 | https://www.zhidx.com/p/category/人工智能 |
| Qwen 官方项目 | 国内官方 GitHub 组织，按 pushed 获取 | https://api.github.com/orgs/QwenLM/repos?sort=pushed&per_page=30 |
| DeepSeek 官方项目 | 国内官方 GitHub 组织，按 pushed 获取 | https://api.github.com/orgs/deepseek-ai/repos?sort=pushed&per_page=30 |
| OpenAI | 海外官方 RSS | https://openai.com/news/rss.xml |
| Google AI | 海外官方 RSS | https://blog.google/technology/ai/rss/ |
| Anthropic | 海外官方公开新闻页 | https://www.anthropic.com/news |
| Hugging Face | 海外官方 RSS | https://huggingface.co/blog/feed.xml |
| GitHub | AI／LLM 项目公开搜索 | https://api.github.com/search/repositories |
| Hacker News | 公开 Algolia 搜索，再过滤 AI 标题 | https://hn.algolia.com/api/v1/search_by_date |

GitHub 搜索参数：`topic:artificial-intelligence pushed:>=YYYY-MM-DD archived:false`（stars 降序，30 条）及 `topic:llm pushed:>=YYYY-MM-DD archived:false`（updated 降序，20 条）。GitHub 项目正文优先读取公开 main／master 分支 README，不将项目最后 push 时间描述成模型发布日。

Hacker News 参数：`query=AI&tags=story&numericFilters=created_at_i>{unix_seconds}&hitsPerPage=50`。不使用百度、Google、Bing 等通用搜索，不绕过登录、付费或访问控制。

智东西列表仅提供月日，使用中国时区及最近过去的年份推断日期；若网站改变结构，适配器报错而不是创建假文章。InfoQ 使用公开页面中已经返回的 Nuxt 数据，不依赖私有登录接口。

候选来源未启用自动获取：

- 掘金：https://juejin.cn/
- 机器之心：https://www.jiqizhixin.com/
- 智源社区：https://hub.baai.ac.cn/

## 离线语音

- 安卓运行库：sherpa-onnx **1.13.8**，官方 AAR：https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar
- AAR SHA-256：`633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96`；原库保存在 `app/libs/`，APK 排除未使用的独立 C/C++ 入口库，保留 JNI 与 ONNX Runtime。
- 音色包：https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-multi-lang-v1_1.tar.bz2
- 包大小 **147,031,220 字节**，解压文件约 **215,321,602 字节**；音色包不随 APK 打包。
- 包 SHA-256：`a1e94694776049035c4f2c6529f003aaece993c76aae9a78995831c3c4dcafc6`。应用验证大小与 SHA-256 后安全解压，拒绝越界路径和链接文件，完整安装后才启用。
- 模型卡：https://huggingface.co/hexgrad/Kokoro-82M-v1.1-zh
- 官方音色映射：https://k2-fsa.github.io/sherpa/onnx/tts/all/Chinese-English/kokoro-multi-lang-v1_1.html
- 当前界面提供 `zf_001`（ID 3）及 `zm_009`（ID 58）试听；不声称这些音色对应真人身份。
- 原模型包和 sherpa-onnx 的授权文本保存在 `docs/licenses/`，运行库／依赖各自遵循其上游授权。模型包内词典和语音资源保持原有文件及授权。

默认 Wi-Fi 下载；也可明确配置允许所有网络。GitHub 下载在部分网络可能不可用，应用提供失败原因和重试，系统中文语音可以继续使用。资源不足时回退系统语音。

## 维护与验证

运行 `python3 tools/verify_sources.py` 下载全部来源样本，再运行 `./gradlew :app:testDebugUnitTest -PliveFixtures="$PWD/build/source-fixtures"` 验证实际页面契约。增加或修改网站应同步更新解析器、真实样本测试及本表，未验证的候选不得默认启用。

真实生成的简短试听文件：`docs/audio/kokoro-female.wav`（约 5.65 秒）与 `docs/audio/kokoro-male.wav`（约 5.83 秒）。试听内容为“这是中文自然语音。学习智能体，掌握实际用法。”，不是新闻播报。
