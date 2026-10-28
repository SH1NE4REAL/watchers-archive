# 漫威词库的 FiveThirtyEight 历史数据来源

核实日期：2026-10-28。此来源提供大范围的英文角色名称、来源页 ID 和版本限定，适合作为专名词库的一个基础层。它不是 Marvel 官方 API，不是完整的当前漫威知识库，也不提供中文名称。

## 实际取得的数据与许可

- 数据项目：[FiveThirtyEight/data](https://github.com/fivethirtyeight/data)。
- 原文件：[comic-characters/marvel-wikia-data.csv](https://github.com/fivethirtyeight/data/blob/master/comic-characters/marvel-wikia-data.csv)。
- 实际长度：**2,406,621 字节**。
- 官方 Git blob SHA-1：`16071d50fcf1484e0824f7df28b2144f35763830`。
- SHA-256：`f30f02ab3575aa145fe32dc0709b7931d72a664de0557150abc43a25113f8277`。
- 原始记录：**16,376 行，16,376 个不同 page_id**，没有用标题去重合并。
- 仓库 [README](https://github.com/fivethirtyeight/data/blob/master/README.md) 明确说明，另有说明的情况除外，数据采用 **CC BY 4.0**，代码采用 MIT。本次读取了完整的 [LICENSE](https://github.com/fivethirtyeight/data/blob/master/LICENSE)，为 Attribution 4.0 International，18,650 字节；主题目录没有另外声明许可证。

原文件和许可文本位于忽略目录 `.tools/marvel-vocabulary/`。生产词库应包含署名、原始来源 URL、CC BY 4.0 许可链接／文本及改动说明。仅采用名称、标识、来源 URL 和版本元数据，没有抓取或复制角色页面全文、剧情、图片。

建议署名：

> FiveThirtyEight; original character-page metadata collected from Marvel Wikia, August 24, 2014. Adapted by The Watcher's Archive for local name matching. CC BY 4.0.

## 时间与覆盖边界

实际读取的 [Comic Characters README](https://github.com/fivethirtyeight/data/blob/master/comic-characters/README.md) 明确记录：

- 角色页面数据采集于 **2014-08-24**。
- 出场次数采集于 **2014-09-02**。
- 首次漫画发行月／年资料提取于 **2014-10-06**。

CSV 中可解析的首次出场年份为 **1939–2013**。这个字段是角色首次出场年，不是数据更新日期或漫画目录覆盖年份。2026-10-28 是本项目取得与核对文件的日期，不能当作源数据已更新至 2026 年的证据。2014 年后的新角色和来源未收录主题仍需要其他来源补齐；此数据也不是团队、地点、物品和事件的完整名单。

源字段包含 `page_id/name/urlslug/ID/ALIGN/EYE/HAIR/SEX/GSM/ALIVE/APPEARANCES/FIRST APPEARANCE/Year`。词库中间层只保留名称与标识相关字段及首发时间元数据，不采用性别、状态、出场次数来推断用户当前漫画剧情。

## 规范化后的中间层

`.tools/marvel-vocabulary/fivethirtyeight-entities.jsonl` 包含：

- **16,376 个独立实体**，ID 使用 `fivethirtyeight:<page_id>`。
- **2,387 个安全英文别名**。剔除了 `resident of the Golden Star`、`Secret Agent` 两个不能作为独立人物身份的泛称。
- 中文名称 **0**，全部标记 `UNTRANSLATED`，没有用机器生成中文名来冒充收录量。
- 22 个不同的明确 Earth 限定值；16,299 条标有 Earth-616，47 条没有明确 Earth 限定。
- 输出文件 13,963,534 字节，SHA-256：`3ae3232a0300dbc3cc9216ad5ec1b05b3c427354ddd7d73960590fb2b61ee033`。

每项保留原始名称、原始 slug、来源 ID、清理后的英文名称、版本限定、其他括注、规范化 Fandom 参考 URL、来源许可及采集日期。URL 从旧 Marvel Wikia slug 映射到 `https://marvel.fandom.com/wiki/...` 作为参考，不表示这些页面已被逐一重新请求或验证可访问。

清理只处理数据本身的 HTML／转义引号、空格与最后的 Earth 限定。带明确身份的常见角色原始括注可以作为别名，例如 `Spider-Man (Peter Parker)` 中的 `Peter Parker`。泛指描述、版本、种族或实体限定不会被单独当角色名，例如 `Nazi`、`North Korean Mutate` 等仅保留为来源限定。

**不能只按英文名称合并中文译名。** 此文件中存在 `Cyclops (Lobe)`、`Cyclops (North Korean Mutate)`、`Cyclops (Nazi)` 等不同 ID，同时 `Scott Summers (Earth-616)` 又是另一条记录。把所有 `Cyclops` 都直接写成“镭射眼／Scott Summers”会错误合并；中文映射需要稳定身份、完整别名或来源对照证据。相同代号的其他人物和不同宇宙继续保留为候选。

本中间层是数据准备结果；生产资源的索引生成、中文来源合并和 Android 实际测试结果应由对应版本验收记录给出。

## 可复现的数据准备

`tools/fetch_fivethirtyeight_marvel.py` 保存了原始 CSV 到 JSONL 的转换过程。只允许固定的 FiveThirtyEight 官方仓库 URL，最多获取一次 CSV 和三份小型说明／许可文件；所有输入核对固定 SHA-256，CSV 另外核对 Git blob SHA-1。源文件有变化时拒绝生成，需重新审查来源后显式更新固定值。不会遍历网站或请求人物页。

已有原始文件时，可完全离线复现本次数据：

```powershell
python tools/fetch_fivethirtyeight_marvel.py --csv .tools/marvel-vocabulary/marvel-wikia-data.csv --readme .tools/marvel-vocabulary/fivethirtyeight-readme.md --license .tools/marvel-vocabulary/fivethirtyeight-license.txt --comic-readme .tools/marvel-vocabulary/comic-characters-readme.md --checked-at 2026-10-28 --output .tools/marvel-vocabulary
```

不提供四个本地路径时，只请求对应缺少的固定源文件。`--checked-at` 是核对日期，不是数据更新日期。

分片生成器 `tools/build_marvel_glossary.py` 接受重复的 `--input`、`--source-manifest`、`--license` 参数。生成 256 个名称索引分片和 256 个实体分片，每片压缩前后都限制为 1 MiB，保存 SHA-256 和精确覆盖统计。英文同名保持不同 ID；不会将某一实体的中文名自动传播到同名人物。测试命令：

```powershell
python -m unittest discover -s tools -p test_build_marvel_glossary.py -v
```

本轮此命令实际通过 16 项测试，涵盖生成器边界以及历史源转换和离线校验；并用 16,376 条实际历史源数据完成过忽略目录下的分片生成检查。该结果不代表 Android 设备测试通过。
