package org.watchersarchive.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp

/** Attribution remains available offline; opening a reference is optional. */
@Composable
internal fun DictionarySourcesDialog(onDismiss: () -> Unit) {
    val uri = LocalUriHandler.current
    AlertDialog(onDismissRequest = onDismiss, title = { Text("词库与资料来源") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("普通英汉释义：ECDICT（skywind3000），MIT 许可。")
            TextButton(onClick = { runCatching { uri.openUri("https://github.com/skywind3000/ECDICT") } }) { Text("ECDICT 来源") }
            Text("漫威英文名称：FiveThirtyEight，comic-characters 数据集；原始 Marvel Wikia 角色资料采集于 2014-08-24，CC BY 4.0。")
            Text("观察者漫格对名称、来源限定和别名进行规范化并制作离线索引；这是历史资料，未逐页更新，也未收录页面正文或图片。", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { runCatching { uri.openUri("https://github.com/fivethirtyeight/data/tree/master/comic-characters") } }) { Text("FiveThirtyEight 来源") }
            TextButton(onClick = { runCatching { uri.openUri("https://creativecommons.org/licenses/by/4.0/") } }) { Text("CC BY 4.0 许可") }
            Text("补充实体、中文名称与别名：Wikidata 社区结构化数据，CC0；经 QLever 公开镜像取得。中文标签不代表已核验的授权出版官译，缺少中文名的条目保留英文。")
            TextButton(onClick = { runCatching { uri.openUri("https://www.wikidata.org/wiki/Wikidata:Licensing") } }) { Text("Wikidata 来源与 CC0 说明") }
            TextButton(onClick = { runCatching { uri.openUri("https://qlever.dev/wikidata") } }) { Text("QLever 数据镜像") }
            Text("专名只提供候选，普通词义继续保留。同名人物、宇宙版本及地区译名需要结合原句辨别；本应用不是漫威官方产品。", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}
