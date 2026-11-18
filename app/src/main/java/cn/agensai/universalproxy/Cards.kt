package cn.agensai.universalproxy

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

/** 首页卡片组：全部数值来自 TelemetryPoller（桌面同一份 telemetry.ts 产出），没数据就显示具体原因，不编造 */

private val LevelGreen = Color(0xFF22C55E)
private val LevelAmber = Color(0xFFF59E0B)
private val LevelRed = Color(0xFFEF4444)
private val LevelGray = Color(0xFF64748B)

private fun levelColor(level: String) = when (level) {
    "good" -> LevelGreen; "warn" -> LevelAmber; "bad" -> LevelRed; else -> LevelGray
}

@Composable
fun DashboardCards(cards: JSONObject?, connected: Boolean, onConnect: () -> Unit, onDisconnect: () -> Unit, onSelectNode: (String) -> Unit) {
    val hero = cards?.optJSONObject("heroTop")
    val power = cards?.optJSONObject("power")
    val monitor = cards?.optJSONObject("monitor")
    val traffic = cards?.optJSONObject("traffic")
    val ranking = cards?.optJSONArray("ranking")
    val groups = cards?.optJSONObject("nodeGroups")
    val rows = cards?.optJSONArray("nodeRows")

    // ① 顶部横带：连接状态 / 下载 / 上传
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(vertical = 14.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (connected) "已连接" else "未连接", fontWeight = FontWeight.Bold, color = if (connected) LevelGreen else LevelGray)
                Text(hero?.optString("runtimeText").orEmpty().ifEmpty { "—" }, fontSize = 11.sp)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("↓ " + (hero?.optString("downSpeed") ?: "—"), color = LevelGreen, fontWeight = FontWeight.SemiBold)
                Text("实时下载", fontSize = 11.sp)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("↑ " + (hero?.optString("upSpeed") ?: "—"), color = LevelAmber, fontWeight = FontWeight.SemiBold)
                Text("实时上传", fontSize = 11.sp)
            }
        }
    }
    Spacer(Modifier.height(10.dp))

    // ② 连接按钮 + 节点信息
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(contentAlignment = Alignment.Center) {
                Surface(shape = RoundedCornerShape(50), color = if (connected) Color(0xFF0E2A1E) else Color(0xFF13233B), modifier = Modifier.size(84.dp)) {
                    Button(onClick = { if (connected) onDisconnect() else onConnect() },
                        colors = ButtonDefaults.buttonColors(containerColor = if (connected) LevelRed else LevelGreen),
                        shape = RoundedCornerShape(50), modifier = Modifier.padding(8.dp).size(68.dp)) {
                        Text(if (connected) "断开" else "连接", fontSize = 14.sp)
                    }
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("节点：" + (power?.optString("nodeName")?.ifEmpty { "未选择节点" } ?: "未选择节点"))
                Text("累计流量：↓" + (traffic?.optString("downTotal") ?: "—") + " ↑" + (traffic?.optString("upTotal") ?: "—"), fontSize = 12.sp)
                Text("活跃连接：" + (traffic?.optInt("active", 0) ?: 0) + " · 采样 " + (monitor?.optString("sampledAgo") ?: "—"), fontSize = 12.sp)
                Text("运行时长：" + (hero?.optString("runtimeText")?.ifEmpty { "—" } ?: "—"), fontSize = 12.sp)
            }
        }
    }
    Spacer(Modifier.height(10.dp))

    // ③ 监控四态徽章（桌面 describeMonitorStatus 同一函数产出）
    monitor?.let { m ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("网络连接监控状态", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("connection" to "连接", "sampling" to "采样", "source" to "数据源", "nodeCheck" to "节点体检").forEach { (key, label) ->
                        val item = m.optJSONObject(key)
                        Badge(containerColor = levelColor(item?.optString("level") ?: "idle")) {
                            Text(item?.optString("label")?.ifEmpty { label } ?: label, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                // 空态：结构化原因 + 建议（桌面同一函数产出，绝不写假数据）
                if (!connected || m.optString("emptyTitle").isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(m.optString("emptyTitle").ifEmpty { "—" }, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    Text(m.optString("emptyDetail"), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    m.optJSONArray("advice")?.let { adv -> for (i in 0 until adv.length()) Text("· " + adv.getString(i), fontSize = 11.sp) }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
    }

    // ④ 出口链路排行（chain 维度，桌面 aggregateConnections）
    if (ranking != null && ranking.length() > 0) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("活跃链路排行", style = MaterialTheme.typography.titleSmall)
                for (i in 0 until ranking.length().coerceAtMost(5)) {
                    val r = ranking.optJSONObject(i) ?: continue
                    Row(Modifier.padding(vertical = 2.dp)) {
                        Text(r.optString("key"), Modifier.weight(1f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("↓" + formatBytesJs(r.optLong("download")) + " ↑" + formatBytesJs(r.optLong("upload")), fontSize = 11.sp)
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
    }

    // ⑤ 地区分组 + 节点行（桌面 regions.ts 同一规则：只认节点名真实关键词）
    if (groups != null && groups.length() > 0) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("按地区分组", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    groups.keys().forEach { g ->
                        Surface(shape = RoundedCornerShape(8.dp), color = Color(0xFF1B2C44), modifier = Modifier.padding(1.dp)) {
                            Text(g + " " + groups.optInt(g), Modifier.padding(horizontal = 8.dp, vertical = 4.dp), fontSize = 11.sp)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
    }
    if (rows != null && rows.length() > 0) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("节点（点按选择）", style = MaterialTheme.typography.titleSmall)
                for (i in 0 until rows.length()) {
                    val n = rows.optJSONObject(i) ?: continue
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                            Text(n.optString("name"), fontSize = 13.sp)
                            Text(n.optString("region") + " · " + n.optString("protocol") + " · " + n.optString("server") + ":" + n.optInt("port"), fontSize = 11.sp)
                        }
                        if (n.optBoolean("selected")) Text("✓", color = LevelGreen) else TextButton(onClick = { onSelectNode(n.optString("id")) }, modifier = Modifier.height(32.dp)) { Text("选择", fontSize = 11.sp) }
                    }
                }
            }
        }
        if (!connected) {
            Spacer(Modifier.height(6.dp))
            Text(cards.optString("emptyReason").orEmpty(), fontSize = 11.sp, color = LevelGray)
        }
    }
}

// 与桌面 formatBytes 同刻度（B/KB/MB/GB/TB，1 位小数）
private fun formatBytesJs(n: Long): String {
    if (n <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var v = n.toDouble(); var i = 0
    while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
    return String.format("%.1f %s", v, units[i])
}
