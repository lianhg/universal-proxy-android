package cn.agensai.universalproxy

import android.content.Intent
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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = NodeStore(this)
        HubCore.init(this)
        nodes = store.nodes()
        status = if (VpnControlService.running) VpnControlService.statusText else "未连接"
        setContent {
            MaterialTheme {
                Scaffold(topBar = { TopAppBar(title = { Text("万能代理客户端 · 安卓") }) }) { pad ->
                    Column(Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState())) {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                Text(if (VpnControlService.running) "● " + status else "○ " + status)
                                Spacer(Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { connectSelected() }, enabled = !busy && nodes.length() > 0) { Text("连接") }
                                    OutlinedButton(onClick = { disconnect() }, enabled = VpnControlService.running) { Text("断开") }
                                }
                            }
                        }
                        Spacer(Modifier.height(16.dp))

                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                Text("导入分享链接 / 订阅内容", style = MaterialTheme.typography.titleMedium)
                                OutlinedTextField(
                                    value = importText, onValueChange = { importText = it },
                                    label = { Text("vmess:// vless:// trojan:// 或粘贴订阅正文") },
                                    modifier = Modifier.fillMaxWidth().height(120.dp)
                                )
                                Spacer(Modifier.height(8.dp))
                                Button(onClick = { doImport() }, enabled = !busy) { Text("解析并导入") }
                            }
                        }
                        Spacer(Modifier.height(16.dp))

                        Text("节点（" + nodes.length() + "）", style = MaterialTheme.typography.titleMedium)
                        for (i in 0 until nodes.length()) {
                            val n = nodes.optJSONObject(i) ?: continue
                            val sel = n.optString("id") == store.selected()
                            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(n.optString("name"), style = MaterialTheme.typography.bodyLarge)
                                        Text(n.optString("protocol") + " · " + n.optString("server") + ":" + n.optInt("port") + " · 内核:" + n.optString("kernel"),
                                            style = MaterialTheme.typography.bodySmall)
                                    }
                                    if (sel) Text("✓ 已选") else TextButton(onClick = { store.setSelected(n.optString("id")) }) { Text("选择") }
                                }
                            }
                        }
                        if (nodes.length() == 0) Text("还没有节点。粘贴链接或订阅正文后点「解析并导入」。", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        status = if (VpnControlService.running) VpnControlService.statusText else "未连接"
        nodes = store.nodes()
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
                    // 订阅地址：用户显式点击才联网拉取（与桌面端同规则）
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
                val out = JSONObject(HubCore.buildSingBoxConfig(nodes.toString(), target.optString("id"), store.mode()))
                if (out.has("error")) { status = "配置生成失败: " + out.getString("error"); return@launch }
                if (out.optString("protocol") != "sing-box-ready") { /* keep simple */ }
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

    private fun configFileGuard(json: String): String = json // 直传；服务内使用

    private fun disconnect() {
        startService(Intent(this, VpnControlService::class.java).apply { action = VpnControlService.ACTION_STOP })
        status = "已断开"
    }
}
