# X-Men by Jonathan Hickman Omnibus (2022)：来源连通性检查

检查日期：2026-10-27。仅检查正常公开元数据与重定向；未修改 App，也未下载漫画正文。

用户报告下载失败，开始阅读显示“暂时无法连接 Pixeldrain，请稍后重试”。

## 已核实

- GetComics 公开搜索接口返回 HTTP200，确认文章 ID **286362**：[原文章](https://getcomics.org/marvel/x-men-by-jonathan-hickman-omnibus-2022/)。
- 该文章公开 content 接口返回 HTTP200。文章标注英文、JPG 页面、2022 年、**1.1 GB**，并说明 2023-12-03 更换了 Kileko-Empire 版本。这个大小是文章标注，尚未通过文件服务器核实精确长度。
- 文章五个正常 `/dls/` 地址均返回 HTTP302，分别指向 Pixeldrain、Terabox、MEGA、MediaFire 和 WeTransfer。

| 来源 | 本次正常访问证据 | 能确认的边界 |
| --- | --- | --- |
| Pixeldrain | 分享地址为 `https://pixeldrain.com/u/1L31kMAB`。公开 info API 和分享页都在产生 HTTP 状态前被重置连接（Windows `10054`），正文读取 0 字节。 | 本机当前无法连通该服务，和手机的错误类型相符；尚不能据此断言文件已删除、限流、需要付款或 schema 有误。 |
| Terabox | GetComics 正常302到 `https://teraboxapp.com/s/1ZpfM3SR8sacNQzsuaWRJRQ`。 | 这是分享入口，没有取得直接文件响应；未实施页面交互或登录。 |
| MEGA | GetComics 正常302到 MEGA 文件分享链接。 | 尚未取得可供当前 reader 直接使用的随机访问原始归档；未实施 MEGA 客户端下载或解密。 |
| MediaFire | 正常302到其 `/file_premium/...cbz/file` 分享页；该页正常 GET 返回 HTTP403，未读取正文。 | 该访问被拒绝；未绕过验证、付费或授权限制。链接文件名带 `.cbz` 不能代替内容格式验证。 |
| WeTransfer | GetComics 正常302到 `https://we.tl/t-x5Xe8ljsWT`。 | 尚未检查后续交互或有效期，没有直接文件响应证据。 |

本次总计 **10 次正常访问尝试，读取 8,174 字节元数据，漫画正文 0 字节**；其中 Pixeldrain 两次连接没有进入 HTTP 响应阶段。没有获得文件的 206、Content-Range、ETag 或精确长度，因此没有进行归档、CRC、页面解码或整本下载验证。原始记录在忽略目录 `artifacts/hickman-source-diagnosis.json`，文章公开元数据快照在同目录。

## 判断与处理方向

当前最明确的阻塞在 Pixeldrain 的网络访问阶段，而不是已经证实的 1.1GB 长度处理错误。现有下载模块使用 Long 偏移并允许远大于 1.1GB 的显式离线下载；500,000,000 字节限制只属于临时页缓存。手机是否有足够空间仍应由实际设备确认，不能从本机检查替代。

现有 `SourceResolver` 对 Pixeldrain 的异常统一显示“暂时无法连接 Pixeldrain”；本次网络失败在 JSON 之前，未发现可据此确定的 API 字段或地址拼写缺陷。下载 UI 的通用错误会掩盖这个来源失败原因，宜保留可安全展示的具体来源提示，避免把网络问题都说成手机空间不足。

主机连通性不能证明手机 Wi-Fi 或蜂窝网络上的情况。恢复 Pixeldrain 正常访问、由来源提供可正常直接访问的其他宿主，或用户经来源网站正常流程下载到手机后导入，是可验证的方向。当前没有证据承诺其他分享入口已可由 App 一键读取；不应通过切换陌生域名、IP、绕过验证或下载完整大文件来假装修复。
