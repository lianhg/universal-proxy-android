package cn.agensai.universalproxy

import java.net.HttpURLConnection
import java.net.URL

/** 订阅拉取：只在用户点击后执行；跟随重定向最多 5 跳；上限 8MB */
object SubscriptionFetcher {
    fun fetch(url: String): String {
        var current = url
        repeat(5) {
            val conn = URL(current).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("User-Agent", "UniversalProxyAndroid/0.1")
            conn.connect()
            val code = conn.responseCode
            if (code in 300..399) {
                val loc = conn.getHeaderField("Location") ?: error("重定向缺少 Location")
                current = URL(URL(current), loc).toString()
                conn.disconnect()
                return@repeat
            }
            if (code != 200) error("HTTP " + code)
            val body = conn.inputStream.use { input ->
                val buf = input.readNBytes(8 * 1024 * 1024 + 1)
                if (buf.size > 8 * 1024 * 1024) error("订阅内容超过 8MB 上限")
                String(buf, Charsets.UTF_8)
            }
            conn.disconnect()
            return body
        }
        error("重定向次数超过 5")
    }
}
