# 漫威词库来源的保守精确对照

核对日期：2026-10-28。本步骤合并相同来源页面明确对应的记录，减少重复候选、保留历史英文身份名称；不使用标题相似度推断身份，也不根据同名复制中文。

## 输入与判定边界

输入分别为 `fivethirtyeight-entities.jsonl` 和 Wikidata 的 `records.jsonl`。各自仍是可独立复现、可核查的原始中间层；合并不会改写它们。

只有同时满足以下条件才合并：

- Wikidata 恰好提供一个 Marvel Fandom 页面。重复列出相同页也按多页拒绝。
- 完整 canonical 页面在两份输入形成全局一对一关系；页面编码和空格／下划线可以等价，但人物、版本、Earth 括注和标题大小写保持不变。
- 两侧类型都明确为 `CHARACTER`，均未标记为真人。
- 显式宇宙字段和页面 Earth 限定不存在冲突。明确 MCU 记录不能合入 Earth-616。
- 不存在会被丢弃或覆盖的独立中文标签冲突。

结果保留 Wikidata 稳定 ID、英文主名、中文名、中文别名及其 `COMMUNITY`／`UNTRANSLATED` 依据。英文别名加入历史来源的名称和别名，去除完全重复字符串。保留 FiveThirtyEight 的明确宇宙限定。每条合并记录额外保存 `sourceIds`、`sourceUrls`、`sourceCrosswalk`，说明是精确页面对照且没有逐页重新请求。未合并记录及其原始字段保持不变。

该规则只是保守的来源对应关系。Wikidata 社区标签不等于官方中文译名；保留的同名候选也不表示属于同一人物。已有人工核对的首选译名应继续具有优先级。

## 本轮实际结果

| 项目 | 数量 |
|---|---:|
| FiveThirtyEight 输入 | 16,376 |
| Wikidata 输入 | 4,213 |
| 输入总记录 | 20,589 |
| 共有精确 canonical 页面 | 1,040 |
| 接受的合并对 | 1,029 |
| 合并对中原已有社区中文 | 417 |
| 完整保留的原始记录 | 18,531 |
| 输出记录 | 19,560 |
| 有匹配但拒绝合并的 Five38 记录 | 11 |

拒绝的主要原因互斥统计：多实体歧义 2、多页面 6、类型不同 1、宇宙冲突 2。单条记录可以同时违反多个规则，manifest 另外记录所有原因，因此所有原因计数不能直接相加为拒绝条数。

拒绝清单包括：Bella Donna／Narda Ravanna（一个 WD 指向两个不同人物）；Cyclops、Colossus、Iceman、War Machine、Morgan Stark、Diamondback（WD 提供多个页面）；Krakoa（类型不同）；Daisy Johnson 与 Gorr（明确 MCU 和 Earth-616 信息冲突）。

特别地，Scott Summers 的 WD 条目含 Earth-616 和 Earth-30847，因此本轮没有将它自动合并。不会把 `Cyclops (Lobe)`、`Cyclops (Nazi)` 等其他人物自动标为“镭射眼”。

合并输出仍有 **947 个含汉字的社区中文名称**，不是新增 417 个独立中文概念；417 是已具有中文的对应记录合并数量。19,560 是不同输出记录数，不能称为完整漫威人物数；它也包含地点、物品、组织和仍待分类的来源记录。

## 可复现命令与验证

无需网络，默认读取上述忽略目录下的两个输入，写入同目录的 `merged-entities.jsonl` 与 `merge-manifest.json`：

```powershell
python tools/merge_marvel_glossary_sources.py --checked-at 2026-10-28
python -m unittest discover -s tools -p test_merge_marvel_glossary_sources.py -v
```

可以用 `--fivethirtyeight`、`--wikidata`、`--output-dir` 显式指定路径。工具只原子替换两个固定输出文件，不遍历清理目录，不访问网络。Manifest 保存两个完整输入 SHA-256、记录数、来源、许可证、署名、合并规则、拒绝原因和输出 SHA-256。

本轮校验值：

```text
FiveThirtyEight JSONL SHA-256:
3ae3232a0300dbc3cc9216ad5ec1b05b3c427354ddd7d73960590fb2b61ee033
Wikidata JSONL SHA-256:
2bafe16eccf362e3854ab87ced8dad0baee84e2e7a127a482e1a2ac9da0898a2
合并 JSONL 字节数: 17,402,712
合并 JSONL SHA-256:
6bdc5b8ee953294e331ce4fbc8a1f419c510047054f37760399472f44057b589
```

16 项专门回归测试实际通过，涵盖多页、多实体、宇宙、类型、真人、中文冲突、完整字段保留、来源哈希和无关文件保护。对全部合并结果调用现有分片生成器在内存中实际验证通过：512 分片、25,812 个名称键、28,301 个映射、0 截断；947 个中文字段全部含汉字。最大解压分片 55,565 字节，压缩分片总计 1,269,935 字节。本检查没有写入生产 assets，也不代表 Android 设备测试通过。

来源许可继续分别适用：[FiveThirtyEight/data](https://github.com/fivethirtyeight/data/tree/master/comic-characters) 的 [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/) 和 [Wikidata CC0](https://www.wikidata.org/wiki/Wikidata:Licensing)。合并结果不能移除 FiveThirtyEight 的署名与历史采集时间说明。
