package cn.agensai.universalproxy

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 桌面同款链路：每秒 GET clash_api /connections，喂给 HubCore（桌面同一份 telemetry.ts）做差分与归一化，
 * 再调 buildDashboardCards 产出全部卡片数据；每 30 帧拉一次 /version。
 * 失败经 ingestPollFailure 归零并写明原因，绝不沿用旧帧。只访问 127.0.0.1 本机控制端口。
 */
object TelemetryPoller {
    private const val TAG = "UPCPoll"
    private const val BASE = "http://127.0.0.1:21811"
    private var job: Job? = null
    private var version = ""

    val cards = MutableStateFlow<JSONObject?>(null)

    /** UI 侧提供当前卡片输入（连接状态、节点名、运行秒数、节点列表、选中项） */
    var inputProvider: () -> JSONObject = { JSONObject() }

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            var tick = 0
            while (isActive) {
                try {
                    val conns = get("/connections")
                    if (tick % 30 == 0) version = parseVersion(get("/version"))
                    HubCore.call("ingestConnections", JSONObject.quote(conns) + "," + JSONObject.quote(version))
                } catch (e: Exception) {
                    Log.w(TAG, "poll fail: " + e.message)
                    HubCore.call("ingestPollFailure", JSONObject.quote(e.message ?: "无法连接控制端口"))
                }
                try {
                    val out = HubCore.call("buildDashboardCards", inputProvider().toString())
                    cards.value = JSONObject(out)
                } catch (_: Exception) { /* WebView 未就绪时跳过本帧 */ }
                tick++
                delay(1000)
            }
        }
    }

    fun stop() {
        job?.cancel(); job = null
        // 不能 runBlocking：onDestroy 走主线程，而 HubCore.call 需要主线程派发，会死锁
        try { TelemetryScope.scope.launch(Dispatchers.Main) { HubCore.call("resetTelemetry", "") } } catch (_: Exception) {}
        cards.value = null
    }

    private fun get(path: String): String {
        val conn = URL(BASE + path).openConnection() as HttpURLConnection
        conn.connectTimeout = 3000; conn.readTimeout = 3000
        conn.setRequestProperty("Accept", "application/json")
        try {
            if (conn.responseCode != 200) throw Exception(path + " HTTP " + conn.responseCode)
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally { conn.disconnect() }
    }

    private fun parseVersion(raw: String): String = try {
        JSONObject(raw).optString("version").let { if (it.isEmpty()) "" else "sing-box " + it }
    } catch (_: Exception) { "" }
}
