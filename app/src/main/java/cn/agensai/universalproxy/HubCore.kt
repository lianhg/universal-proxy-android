package cn.agensai.universalproxy

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * 无头 WebView 承载 hub.bundle.js —— 桌面端 src/core/hub 的同一份解析代码（非重写）。
 * 只跑纯计算：不加载页面（about:blank）、不联网、不执行任何导入内容。
 */
@SuppressLint("StaticFieldLeak")
object HubCore {
    private var web: WebView? = null
    @Volatile private var ready = false
    @Volatile private var loadError: String? = null
    private val waiting = java.util.concurrent.ConcurrentLinkedQueue<(String) -> Unit>()

    @SuppressLint("SetJavaScriptEnabled")
    fun init(context: Context) {
        if (web != null) return
        WebView.setWebContentsDebuggingEnabled(true)
        val w = WebView(context.applicationContext)
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = false
        w.settings.allowFileAccess = false
        w.settings.allowContentAccess = false
        w.settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
        w.addJavascriptInterface(object {
            @JavascriptInterface
            fun sendResult(json: String) {
                val cb = waiting.poll()
                if (cb != null) cb(json) else Log.w(TAG, "sendResult with no waiter")
            }
        }, "Android")
        w.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(cm: ConsoleMessage): Boolean {
                Log.i(TAG, "console[" + cm.messageLevel() + "]: " + cm.message())
                return true
            }
        }
        w.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: android.webkit.WebView, url: String) {
                val js = try {
                    view.context.assets.open("hub.bundle.js").bufferedReader().use { it.readText() }
                } catch (e: Exception) {
                    loadError = "读取 hub.bundle.js 失败: " + e.message
                    Log.e(TAG, loadError!!, e)
                    return
                }
                view.evaluateJavascript(js) {
                    // 自检：确认桥已就绪
                    view.evaluateJavascript("typeof globalThis.__HUB__==='object' && typeof globalThis.__HUB__.importText==='function'") { r ->
                        if (r?.contains("true") == true) { ready = true; Log.i(TAG, "hub ready (" + js.length + " bytes)") }
                        else { loadError = "hub.bundle.js 注入后 __HUB__ 未就绪: " + r; Log.e(TAG, loadError!!) }
                    }
                }
            }
        }
        web = w
        ready = false
        loadError = null
        w.loadUrl("about:blank")
    }

    private const val TAG = "HubCore"

    /** 调 __HUB__ 上的方法；argsJson 是已序列化好的 JSON 参数串 */
    suspend fun call(fn: String, argsJson: String): String = withContext(Dispatchers.Main) {
        val w = web ?: return@withContext "{\"__hubError\":\"WebView 未初始化\"}"
        var waited = 0L
        while (!ready && waited < 15_000) { delay(50); waited += 50 }
        if (!ready) {
            val why = loadError ?: "hub.bundle.js 加载超时"
            Log.e(TAG, "call $fn blocked: $why")
            return@withContext "{\"__hubError\":" + org.json.JSONObject.quote(why) + "}"
        }
        // 入口函数有的返回对象（importText），有的返回字符串（buildSingBoxConfig/ingest…）；
        // 统一：字符串原样传，对象才 JSON.stringify，避免双重编码
        val payload = "(function(){try{var v=globalThis.__HUB__." + fn + "(" + argsJson + ");" +
            "return (typeof v==='string')?v:JSON.stringify(v);}" +
            "catch(e){return JSON.stringify({__hubError:String((e&&e.message)||e)});}})()"
        val res = withTimeoutOrNull(15_000) {
            suspendCancellableCoroutine<String> { cont ->
                waiting.add { s -> cont.resume(s) }
                w.evaluateJavascript("Android.sendResult(" + payload + ")") {}
            }
        }
        res ?: JSONObjectError("JS 调用超时（" + fn + "）")
    }

    private fun JSONObjectError(msg: String): String =
        org.json.JSONObject().put("__hubError", msg).toString()

    fun q(s: String): String = org.json.JSONObject.quote(s)

    suspend fun importText(text: String, source: String): String =
        call("importText", q(text) + ",{source:" + q(source) + "}")

    suspend fun buildSingBoxConfig(nodesJson: String, selectedId: String, mode: String): String =
        call("buildSingBoxConfig", nodesJson + "," + q(selectedId) + "," + q(mode))
}
