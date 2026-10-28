# Wikidata 漫威名称来源记录

检查日期：2026-10-28。用途为离线英文助手的实体名称与别名候选，不复制人物传记、剧情正文、图片或 Fandom 页面正文。

Wikidata 主名字空间的结构化数据依 [Wikidata Licensing](https://www.wikidata.org/wiki/Wikidata:Licensing) 以 [CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/) 提供。它是社区数据，不能把中文 label 标成漫威出版社的正式译名；保留实体 ID、原文变体、来源 URL、检查日期和 `COMMUNITY`／`UNTRANSLATED` 证据级别。外链目标页面的正文、图片和其他内容不因此取得 CC0 许可。

## 实际取得的数据

正常访问公共 [QLever Wikidata SPARQL 镜像](https://qlever.dev/api/wikidata)，没有使用需凭据的接口或绕过访问限制。镜像是第三方运行的查询服务，并非 Wikidata 官方端点；[QLever 项目](https://github.com/ad-freiburg/qlever)说明其查询引擎。其 `cmd=stats` 响应在此次获取时自报：Wikidata entity TTL 基线日期 2026-08-10、lexeme 基线日期 2026-08-07，并标注 `updated LIVE`。这只是镜像报告，不能据此证明所有实体均已同步到最新编辑。

选择范围是以下明确结构化声明的并集：`P6262` 中以 `marvel:` 开头的 Fandom article ID；`P1080=Q931597` 的 Marvel Comics 共享宇宙；`P1080=Q642878` 的 MCU。`scope-identities` 响应已核对 Q931597 的英文描述和 P6262 的属性名。范围仍可能含现实人物、电影及不适合阅读助手的其他实体；下游必须筛选，不得把原始条目数等同于角色数或“漫威全集”。MCU 与漫画宇宙也不能自动合并。

本次使用的 9 个成功 HTTP 200 JSON 响应共 **4,263,366 字节**，原始请求起始时间覆盖 2026-10-28 08:00:07 至 08:06:43 UTC。没有导入完整 Wikidata 数据库。2026-10-28 08:11:59 UTC 的最终导出仅重用这 9 个本地响应快照，**未再次请求网络**。

导出为 **4,213 个带英文 label 的独立 QID**；181 个无英文 label 的范围内实体跳过。原始范围包含 320 个声明为现实人类的条目。3,388 个导出实体带明确 `marvel:` 外链对应关系；此关系用来核对相同页面，不能仅依标题相似合并。类型分类为 CHARACTER 1,855、PLACE 400、ITEM 229、ORGANIZATION 168、TEAM 6、UNKNOWN 1,555；分类是来源 P31/P279 路径的机械归类，需要下游谨慎筛选。

其中 **947 个实体有真正含汉字的中文名称 label**，469 个有含汉字的其他中文名称／别名。另有 15 个实体的中文语言标签下出现 28 个不含汉字的 label，例如语言为 `zh` 但值仍是英文。这些字符串保存在原始 `labelVariants`／别名中，不能计作已翻译中文，也不用于 `chineseName`。中文显示名按 zh-hans、zh-cn、zh、zh-hant、zh-tw 的顺序选第一个含汉字的 source label，找不到即标记未译。不会用别的实体的译名、机器猜译或中文 alias 替换来源 label。

## 可追溯性与重建

构建素材位于被 Git 忽略的 `.tools/marvel-vocabulary/wikidata/`：`requests.json` 记录完整请求 URL、查询、HTTP 状态、响应字节数、SHA-256 和快照重用情况；JSON 响应原件分别保存。`manifest.json` 记录查询哈希、响应哈希、原始请求时间、镜像基线、筛选范围、数量和导出哈希，适合随生成的词库作为紧凑来源清单保留。原始范围导出不能直接等同于最终安装包词库。

最终 `records.jsonl` SHA-256：

`2bafe16eccf362e3854ab87ced8dad0baee84e2e7a127a482e1a2ac9da0898a2`

复用已下载快照的命令：

```powershell
python tools/fetch_marvel_wikidata.py --reuse-snapshots --checked-at 2026-10-28
```

不带 `--reuse-snapshots` 才会重新向公共镜像请求；默认最多 30 个请求、每个响应最多 12 MiB、单次超时 25 秒，范围最多 12,000 个实体（可显式调整但不超过 20,000）。这不是应用每次打开都需要执行的步骤。镜像查询失败、缺中文或分类不明时不补造数据。

实际验证：`python -m unittest tools.test_fetch_marvel_wikidata -v` 的 4 项回归通过，覆盖纯英文 zh label 不冒充中文、优先语言的英文值不遮蔽其他语言的汉字、别名不擅自升为来源 label，以及与词库构建器相同的汉字判断。另对 4,213 条导出逐条执行 `canonical_entity`，全部符合当前构建器格式约束。此项未执行 Gradle、Android 设备或 OCR 识别测试。
