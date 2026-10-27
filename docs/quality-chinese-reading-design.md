# 中文阅读：质量优先、手机独立运行的方案

评审日期：2026-10-27。本文是设计方案，尚未接通付费翻译接口，也未实现图内中文覆盖。已读取下列官方文档；文档可访问不代表用户手机能访问相应推理服务，译文质量、费用和延迟仍需小样本实测。

建议先采用“手机本地 OCR → 在线文本翻译 → 可逆中文覆盖”，比较 **阿里云百炼 Qwen-MT Plus** 与 **DeepL** 后选默认服务。用户无需开电脑或搭建局域网服务。OCR 失败的特殊字体，另提供明确选择的图片/局部图片上传与视觉模型辅助，不能默认上传所有漫画页面。

## 两个可选服务

| 候选 | 已核实的官方能力 | 对本项目的用法与边界 |
| --- | --- | --- |
| DeepL 文本翻译 API | HTTPS `POST /v2/translate`；文本数组按提交顺序返回；提供 `context`、`glossary_id`/`glossary_ids`；语言表列出简体中文 `ZH-HANS` 及术语表能力。 | 发送本地识别的文本数组，用邻近实际对白作为 context，角色/地名用已校对词库。数组各项独立翻译，不能假定同一请求自动共享上下文。具体账户、英中词典语言对和模型能力接入时再次查询确认。 |
| 阿里云百炼 Qwen-MT | 官方列出 `qwen-mt-plus`，定位为该系列偏高质量的选项；支持简体中文 `Chinese`/`zh`，提供 `translation_options.terms`、`tm_list`、`domains`；公开 HTTPS HTTP 调用和地域/业务空间地址。 | 作为国内地域服务的优先候选，手机可直接请求。MT API 仅接受一个 user 消息，不支持多轮与 system 消息；不能按通用聊天模型随意拼消息，也不假定其保留 JSON 或段落 ID。`domains` 暂只支持英文。官方产品定位不是本项目漫画质量胜出的实测证明。 |

DeepL 官方接口总请求体限制为 128KiB；应用还应采用更小的页/段落预算。`context` 是周边文档内容，不能当作“始终这样翻译”的指令；固定人名应走术语表。Qwen-MT 的术语干预、翻译记忆和领域提示与 DeepL 的原始 context 能力不同，适配层需要声明能力，不能假装完全等价。

阿里云视觉理解文档支持图片与文本输入、文字识别/信息抽取，可作为难字与场景理解的扩展。它属于单独的视觉 provider，不代表 Qwen-MT 文本翻译模型接受图片；视觉模型也可能误读或添加文字。

## 账号、网络与密钥

官方 REST 接口提供了手机直连 HTTPS 的技术路径，Android 无需桌面 SDK。所选服务仍需要账户、可用 API Key、相应地域/业务空间和额度。**本次没有提供或使用 Key，没有开通套餐、调用付费推理、验证免费额度或测试大陆运营商网络。** 不能承诺免费或任意网络都可达。

- 个人使用可采用用户自带密钥（BYOK）：用户在手机输入自己的服务凭据；用 Android Keystore 管理的密钥加密后保存在 App 私有存储，运行时只为请求解密，不写入 APK、源码、日志或下载记录。Keystore 保护加密密钥，不是把任意 API 字符串直接硬塞进 KeyStore。
- 面向多人使用时可采用托管的公网后端：App 使用短期会话凭证，供应商长期 Key 留在服务端，并做配额、计费和访问控制。该后端无需运行在用户电脑上，但需要另外部署和维护。
- 先只上传识别文字及有限的真实上下文，页面原图留在手机；使用视觉模型时，额外展示并取得上传该页/局部图片的明确选择。供应商数据处理条款与选定地域在启用服务前复核。

## 接入契约与阅读体验

新增可替换的 `TranslationProvider`，能力说明包含原始上下文、术语表、翻译记忆和图片输入是否支持。一次翻译任务包含 `documentVersion`、`pageId`、源/目标语言、稳定 `blockId`、识别原文、图片坐标、有限上下文、词库版本及 provider/model 配置；返回结果必须对应每个已提交的 block，并保留失败项。

- DeepL 按请求数组索引映射到本地 blockId，校验返回数量后才提交。Qwen-MT 可先每个气泡一个受限请求，由客户端保留映射；不把 blockId 混入待翻译文本并寄希望于模型原样返回。整页上下文通用/视觉模型另作接口，严格校验 ID、漏段、重复段和额外输出。
- 角色名、组织名、地名采用用户可校对的词库；先缓存已经确认的译法，再通过 provider 支持的术语/记忆机制应用。页面阅读顺序由坐标和必要校对确定，不能让翻译输出重排原图位置或编造剧情背景。
- 阅读器一次开启“中文模式”，自动处理当前页与后面一页；图像与中文层共用缩放/平移坐标。原图不被覆写，随时切回英文。翻译准确性与气泡边界、遮盖、换行排版分别验收。
- 下载完成后的“整部预译”是另一次明确操作，显示进度、预计用量、暂停/继续；逐页串行处理，不常驻整本 Bitmap。缓存键包含文件 ETag/页身份、OCR 版本、provider/model、目标语言、词库/提示版本。失败只重试该页；限流退避、总用量上限与任务取消必须存在。
- 临时译文与派生图片计入现有 500,000,000 字节预算；明确保留的整部译文可作为离线派生文件单独管理。漫画目录、进度、书签和笔记保持独立。

下一步需要选择服务和配置账户/额度，再用自建或用户选定的小样本比较：对话含义、人名一致性、口语/双关、OCR 错字、漏段、上下文、气泡对应、单页用量和延迟。未完成这个比较前，不宣布某家服务已经解决漫画汉化质量。

## 官方资料

- [DeepL 翻译请求、上下文、术语与返回顺序](https://developers.deepl.com/api-reference/translate/request-translation)
- [DeepL 支持语言及功能](https://developers.deepl.com/docs/getting-started/supported-languages)
- [DeepL context 的用途与限制](https://developers.deepl.com/docs/learning-how-tos/examples-and-guides/how-to-use-context-parameter)
- [阿里云 Qwen-MT 模型选型、简体中文与调用方式](https://help.aliyun.com/zh/model-studio/machine-translation)
- [Qwen-MT API、terms/tm_list/domains 与地域地址](https://help.aliyun.com/zh/model-studio/qwen-mt-api)
- [阿里云视觉理解与文字识别](https://help.aliyun.com/zh/model-studio/vision)
- [Android Keystore](https://developer.android.com/privacy-and-security/keystore)（机制参考；本轮未实现密钥存储）
