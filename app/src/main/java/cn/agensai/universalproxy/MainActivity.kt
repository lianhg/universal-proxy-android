package cn.agensai.universalproxy

import android.content.Intent
import android.util.Log
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {

    companion object { private const val TAG = "UPCMain" }

    private lateinit var store: NodeStore
    private var pendingStart: (() -> Unit)? = null

    private val vpnPrepare = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) pendingStart?.invoke() else status = "未授权 VPN，无法连接"
        pendingStart = null
    }

    private var status by mutableStateOf("未连接")
    private var nodes by mutableStateOf(JSONArray())
    private var importText by mutableStateOf("")
    private var busy by mutableStateOf(false)
    private var mode by mutableStateOf("bypass-private")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = NodeStore(this)
        HubCore.init(this)
        nodes = store.nodes()
        mode = store.mode()
        status = if (VpnControlService.running) VpnControlService.statusText else "未连接"

        // 卡片数据输入：连接状态 / 节点名 / 运行秒数 / 节点列表 / 选中项（全部真实值）
        TelemetryPoller.inputProvider = {
            val secs = if (VpnControlService.running && VpnControlService.startedAtMs > 0)
                (System.currentTimeMillis() - VpnControlService.startedAtMs) / 1000 else 0
            JSONObject()
                .put("connected", VpnControlService.running)
                .put("nodeName", VpnControlService.nodeNameOrEmpty())
                .put("listenPort", JSONObject.NULL)
                .put("runningSeconds", secs)
                .put("nodesJson", store.nodes().toString())
                .put("selectedId", store.selected())
        }

        setContent {
            MaterialTheme {
                val cards by TelemetryPoller.cards.collectAsState()
                Scaffold(topBar = { TopAppBar(title = { Text("万能代理客户端 · 安卓") }) }) { pad ->
                    Column(Modifier.padding(pad).padding(12.dp).verticalScroll(rememberScrollState())) {
                        // 卡片组（桌面同款数据链路）
                        DashboardCards(
                            cards = cards,
                            connected = VpnControlService.running,
                            onConnect = { connectSelected() },
                            onDisconnect = { disconnect() },
                            onSelectNode = { id -> store.setSelected(id); nodes = store.nodes() },
                        )
                        Spacer(Modifier.height(12.dp))

                        // 状态与分流模式
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp)) {
                                Text(if (VpnControlService.running) "● " + status else "○ " + status)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("分流：", fontSize = 13.sp)
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        listOf("bypass-private" to "局域网直连", "global" to "全局代理").forEach { (key, label) ->
                                            if (mode == key) Button(onClick = {}, enabled = false) { Text(label, fontSize = 11.sp) }
                                            else OutlinedButton(onClick = { mode = key; store.setMode(key) }) { Text(label, fontSize = 11.sp) }
                                        }
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))

                        // 导入
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp)) {
                                Text("导入分享链接 / 订阅内容", style = MaterialTheme.typography.titleMedium)
                                OutlinedTextField(
                                    value = importText, onValueChange = { importText = it },
                                    label = { Text("vmess:// vless:// trojan:// 或粘贴订阅正文") },
                                    modifier = Modifier.fillMaxWidth().height(110.dp)
                                )
                                Spacer(Modifier.height(8.dp))
                                Button(onClick = { doImport() }, enabled = !busy) { Text("解析并导入") }
                            }
                        }
                        Spacer(Modifier.height(12.dp))

                        // 节点列表（精简版：卡片组里已有选择行，这里保留删除入口）
                        Text("管理（" + nodes.length() + "）", style = MaterialTheme.typography.titleMedium)
                        for (i in 0 until nodes.length()) {
                            val n = nodes.optJSONObject(i) ?: continue
                            val sel = n.optString("id") == store.selected()
                            Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                                Row(Modifier.padding(horizontal = 12.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text((if (sel) "✓ " else "") + n.optString("name"), Modifier.weight(1f), fontSize = 13.sp)
                                    TextButton(onClick = {
                                        val arr = JSONArray()
                                        for (j in 0 until nodes.length()) if (j != i) nodes.optJSONObject(j)?.let(arr::put)
                                        store.saveNodes(arr); nodes = store.nodes()
                                    }) { Text("删除", fontSize = 11.sp, color = MaterialTheme.colorScheme.error) }
                                }
                            }
                        }
                        if (nodes.length() == 0) Text("还没有节点。粘贴链接或订阅正文后点「解析并导入」。", fontSize = 12.sp)
                    }
                }
            }
        }
    }


    override fun onResume() {
        super.onResume()
        status = if (VpnControlService.running) VpnControlService.statusText else "未连接"
        nodes = store.nodes()
        mode = store.mode()
        // 打开界面时若内核在跑但轮询没在（进程被杀重启等），补拉一次
        if (VpnControlService.running) TelemetryPoller.start(TelemetryScope.scope)
    }

    private fun doImport() {
        if (importText.isBlank()) return
        busy = true
        lifecycleScope.launch {
            try {
                val rep = JSONObject(HubCore.importText(importText.trim(), "paste"))
                if (rep.has("__hubError")) { status = "解析失败: " + rep.getString("__hubError"); return@launch }
                val found = rep.optJSONArray("nodes") ?: JSONArray()
                val requiresFetch = rep.optJSONObject("requiresFetch")
                if (found.length() == 0 && requiresFetch != null) {
                    status = "识别为订阅地址，拉取中…"
                    val url = requiresFetch.optString("url")
                    val body = withContext(Dispatchers.IO) { SubscriptionFetcher.fetch(url) }
                    val rep2 = JSONObject(HubCore.importText(body, "subscription"))
                    if (rep2.has("__hubError")) { status = "订阅解析失败: " + rep2.getString("__hubError"); return@launch }
                    val added = store.mergeNodes(rep2.optJSONArray("nodes") ?: JSONArray())
                    status = "订阅导入完成：新增 " + added + " 个节点"
                } else if (found.length() == 0) {
                    status = "没有解析出节点: " + (rep.optJSONArray("rejected")?.optJSONObject(0)?.optString("reason") ?: "内容无法识别")
                } else {
                    val added = store.mergeNodes(found)
                    status = "导入完成：新增 " + added + " / 解析 " + found.length() + " 个"
                }
                nodes = store.nodes()
                importText = ""
            } catch (e: Exception) {
                status = "导入失败: " + (e.message ?: e.javaClass.simpleName)
            } finally { busy = false }
        }
    }

    private fun connectSelected() {
        val sel = store.selected()
        val target = (0 until nodes.length()).map { nodes.optJSONObject(it) }
            .firstOrNull { it?.optString("id") == sel } ?: nodes.optJSONObject(0)
        if (target == null) { status = "没有可连接的节点"; return }
        busy = true
        lifecycleScope.launch {
            try {
                val raw = HubCore.buildSingBoxConfig(nodes.toString(), target.optString("id"), store.mode())
                val out = JSONObject(raw)
                if (out.has("error")) { status = "配置生成失败: " + out.getString("error"); return@launch }
                val configJson = out.getString("config")
                val nodeName = out.optString("nodeName")
                val prep = android.net.VpnService.prepare(this@MainActivity)
                if (prep != null) {
                    pendingStart = { startVpn(configJson, nodeName) }
                    vpnPrepare.launch(prep)
                    return@launch
                }
                startVpn(configJson, nodeName)
            } catch (e: Exception) {
                Log.e(TAG, "connect failed", e)
                status = "连接失败: " + (e.message ?: e.javaClass.simpleName)
            } finally { busy = false }
        }
    }

    private fun startVpn(configJson: String, nodeName: String) {
        val i = Intent(this, VpnControlService::class.java).apply {
            action = VpnControlService.ACTION_START
            putExtra(VpnControlService.EXTRA_CONFIG_JSON, configFileGuard(configJson))
            putExtra(VpnControlService.EXTRA_NODE_NAME, nodeName)
        }
        startForegroundService(i)
        status = "连接中 · " + nodeName
    }

    private fun configFileGuard(json: String): String = json

    private fun disconnect() {
        startService(Intent(this, VpnControlService::class.java).apply { action = VpnControlService.ACTION_STOP })
        status = "已断开"
    }
}
