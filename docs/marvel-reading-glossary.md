# 英文助手的漫威专名词库

日期：2026-10-28，版本0.10.0。词库与普通英汉词典并列，用于当前阅读页的名称候选，不推断剧情，不将社区中文名标作官译。0.9.1的20项人工词表与卡片避让验收保留在[历史记录](0.9.1-verification.md)。

## 实际打包覆盖

| 项目 | 数量与边界 |
|---|---|
| 原始输入 | FiveThirtyEight 16,376条；Wikidata 4,213条 |
| 精确来源对照 | 合并1,029对；11条有匹配但存在歧义或冲突，拒绝合并 |
| 分片记录 | 19,560条；不同来源仍可能重复，不能称为不同人物总数 |
| 类型 | 角色17,202、组织168、团队6、地点400、物品229、待分类1,555 |
| 名称索引 | 25,812个归一化检索键，28,301个名称到记录的映射 |
| 中文主名 | 947条实际含汉字的社区标签；18,613条未译 |
| 人工优先层 | 原有20条常用／用户指定译名；与上层有重叠，不能直接相加 |
| 安装资源 | 256个别名分片和256个实体分片；压缩分片共1,298,748字节，最大解压分片57,136字节 |

英文代号、全名、别名和已知中文变体均可检索，例如Victor von Doom、Doreen Green、Kamala Khan、Galactus、Krakoa。未知译名不会用机器生成的名字充数。社区标签存在繁简体、地区用语和质量差异；已有人工优先名称继续优先。Cyclops的“镭射眼”仍为USER_PREFERRED，不被社区首选“獨眼龍”覆盖。

类型反映来源的机械分类。Wikidata原始范围包含320条现实人物及其他与Marvel相关的现实主题，保留为普通专名资料，不能因此视为虚构角色。待分类条目不伪装成角色；MCU与漫画宇宙明确分开。

## 来源与许可

- [FiveThirtyEight comic-characters](https://github.com/fivethirtyeight/data/tree/master/comic-characters)：CC BY 4.0，Marvel Wikia角色快照采集于2014-08-24。提供历史英文身份和版本限定，不是2026年完整目录，不含中文。[来源、哈希与复现](marvel-fivethirtyeight-source.md)。
- [Wikidata](https://www.wikidata.org/wiki/Wikidata:Licensing)：社区结构化数据CC0，经第三方[QLever镜像](https://qlever.dev/wikidata)取得。范围为有明确marvel:外链或Marvel/MCU宇宙声明的条目。镜像自报2026-08-10基线并标注LIVE，不能保证每条实时同步。[请求证据与覆盖](marvel-wikidata-source.md)。
- [来源对照](marvel-glossary-source-merge.md)：仅允许完整Marvel Fandom页面严格一对一、单页面、相同角色类型、无真人、无宇宙或中文冲突的配对。不按标题相似度合并。原始中间层保留完整对照，生产分片也保存合并记录的双方来源ID和URL。

只导入名称、标识、关系和来源限定，未抓取传记、剧情或图片。原始快照放在被忽略的.tools/marvel-vocabulary；生成资源含分片SHA-256、输入哈希、来源清单、变更说明及CC BY许可全文。应用“设置 → 关于与版本 → 词库与资料来源”可离线查看署名，并主动打开来源和许可。

Marvel英文链接与中文证据分离。0.9.1正常请求Cyclops页面返回403，未绕过；保留链接不能当作已读取正文或核验官译的证据。

## 匹配与显示

- 只接受当前页已有词语或阅读器生成的有效连续选区。大小写、全半角、标点、已知缩略形式及所有格统一归一化，拒绝伪造／过期页身份。
- 在所点词所在OCR行找最长已登记名称，每窗口最多5词，最多查询64个窗口，不跨行猜人物。例如点Doom可核对同一行的Victor von Doom。
- 常见功能词不单独查询扩展实体，避免实际数据中A、It、One等角色误干扰普通对白。完整多词名称仍可匹配；单点the等词不扩展到周围名称。
- 最多显示3个候选，优先完整长名、人工条目、有中文的条目；条件相同时漫画阅读中的MCU候选排后，仍保留为独立候选。这只是排序，不确认身份。
- Storm、Thing、Vision、Beast等保留普通词义。X-Men书名只提供弱提示，不证明当前原句的身份。Cycl0ps等OCR错字不猜译。
- 紧凑卡片只统计实际有中文主名的候选。仅英文候选不覆盖普通词义，会提示“中文名待补”。展开可查看类型、证据、宇宙、中文别名与独立资料入口。
- 纯英文zh标签可作为检索别名，但不计中文覆盖，也不冠以“其他中文名称”显示。没有生词本。

## 运行边界

安装后专名完全离线可查，不上传页面、不用云密钥、不依赖电脑IP。普通英汉词典和可选设备端对白翻译保持独立，专名命中不等于整句翻译或剧情消歧。

查询在IO dispatcher执行，只读所需哈希分片。别名、实体各保留最多4个LRU分片；每个分片编码／解码限1MiB，清单限512KiB，字段和候选数有界。格式、长度、gzip和SHA-256错误会明确提示扩展词库不可用，保留基础词表和普通释义，不把损坏伪装成未收录。

退出阅读器时索引使用非阻塞close；循环、分片和读取块之间检查取消，取消不缓存成资源损坏，关闭后的在途查询负责释放流和索引缓存。

这是安装资源，不属于500,000,000字节漫画临时缓存。本轮未修改Range／ETag、CRC、缓存预算、页面租约、离线正文或阅读持久数据。

## 重建与验证

先按来源文档取得可核验输入，再离线生成：

```powershell
python tools/merge_marvel_glossary_sources.py --checked-at 2026-10-28
python tools/build_marvel_glossary.py --input .tools/marvel-vocabulary/merged-entities.jsonl --source-manifest .tools/marvel-vocabulary/fivethirtyeight-manifest.json --source-manifest .tools/marvel-vocabulary/wikidata/manifest.json --source-manifest .tools/marvel-vocabulary/merge-manifest.json --license .tools/marvel-vocabulary/fivethirtyeight-license.txt --output app/src/main/assets/marvel-glossary
python -m unittest discover -s tools -p 'test_*.py' -v
```

生成器固定gzip时间／OS头、确定性排序，清单最后原子替换；不清理无关文件。中文主名必须含汉字，别名／候选截断明确统计，本次无截断。

实际Android编译、生产AssetManager查词路径、Compose显示及取消回归结果见[0.10.0验收](0.10.0-verification.md)。主机测试不等于手机实测，也不证明所有艺术字体均可被OCR识别。
