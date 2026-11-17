package cn.agensai.universalproxy

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * 无头 WebView 承载 hub.bundle.js —— 桌面端 src/core/hub 的同一份解析代码（非重写）。
 * 只跑纯计算：不加载页面、不联网、不执行任何导入内容。
 */
@SuppressLint("StaticFieldLeak")
object HubCore {
    private var web: WebView? = null
    @Volatile private var ready = false
    private val waiting = ArrayDeque<(String) -> Unit>()

    @SuppressLint("SetJavaScriptEnabled")
    fun init(context: Context) {
        if (web != null) return
        val w = WebView(context.applicationContext)
        w.settings.javaScriptEnabled = true
        w.addJavascriptInterface(object {
            @JavascriptInterface
            fun sendResult(json: String) {
                waiting.removeFirstOrNull()?.invoke(json)
            }
        }, "Android")
        web = w
        w.loadUrl("about:blank")
        w.post {
            val js = context.assets.open("hub.bundle.js").bufferedReader().use { it.readText() }
            w.evaluateJavascript(js) { ready = true }
        }
    }

    /** 调 __HUB__ 上的方法；argsJson 是已序列化好的 JSON 参数串 */
    suspend fun call(fn: String, argsJson: String): String = withContext(Dispatchers.Main) {
        while (!ready) delay(50)
        val payload = "JSON.stringify((function(){try{return globalThis.__HUB__." + fn + "(" + argsJson + ")}" +
            "catch(e){return {__hubError:String((e&&e.message)||e)}}})())"
        suspendCancellableCoroutine { cont ->
            waiting.addLast { s -> cont.resume(s) }
            web!!.evaluateJavascript("Android.sendResult(" + payload + ")") {}
        }
    }

    fun q(s: String): String = org.json.JSONObject.quote(s)

    suspend fun importText(text: String, source: String): String =
        call("importText", q(text) + ",{source:" + q(source) + "}")

    suspend fun buildSingBoxConfig(nodesJson: String, selectedId: String, mode: String): String =
        call("buildSingBoxConfig", nodesJson + "," + q(selectedId) + "," + q(mode))
}
