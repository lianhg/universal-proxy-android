package cn.agensai.universalproxy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import bridge.Bridge

/**
 * VpnService：安卓唯一合法接管全局流量的方式。
 * establish() 拿到 tun fd → 直接交给内嵌 sing-box（gomobile AAR，库形态跑在本进程）。
 * fd 所有权：不 adopt、不提前 close；Bridge.stop() 返回后才关 pfd。
 */
class VpnControlService : VpnService() {

    companion object {
        const val ACTION_START = "cn.agensai.universalproxy.START"
        const val ACTION_STOP = "cn.agensai.universalproxy.STOP"
        const val EXTRA_CONFIG_JSON = "config_json"
        const val EXTRA_NODE_NAME = "node_name"

        @Volatile var running = false
        @Volatile var statusText = ""
    }

    private var pfd: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopAll(); return START_NOT_STICKY }
            ACTION_START -> {
                val configJson = intent.getStringExtra(EXTRA_CONFIG_JSON) ?: return START_NOT_STICKY
                val nodeName = intent.getStringExtra(EXTRA_NODE_NAME) ?: ""
                startCompatNotification(nodeName)
                Thread {
                    try {
                        pfd = Builder()
                            .setSession("万能代理")
                            .addAddress("172.19.0.1", 30)
                            .addAddress("fdfe:dcba:9876::1", 126)
                            .addDnsServer("223.5.5.5")
                            .addRoute("0.0.0.0", 0)
                            .addRoute("::", 0)
                            .setBlocking(true)
                            .setMtu(9000)
                            .addDisallowedApplication(packageName) // 永不接管自己
                            .establish() ?: error("VPN 建立被拒绝（可能已有其他 VPN 在运行）")

                        // 配置 JSON 内容 + tun fd 交给内嵌 sing-box（Go error → Java Exception）
                        Bridge.start(configJson, pfd!!.fd)
                        running = true
                        statusText = "已连接 · " + nodeName
                        updateNotification()
                    } catch (e: Throwable) {
                        statusText = "连接失败: " + (e.message ?: e.javaClass.simpleName)
                        running = false
                        try { pfd?.close() } catch (_: Throwable) {}
                        pfd = null
                        updateNotification()
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }.start()
                return START_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private fun stopAll() {
        try { Bridge.stop() } catch (_: Throwable) {}
        try { pfd?.close() } catch (_: Throwable) {}
        pfd = null
        running = false
        statusText = "已断开"
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, "vpn")
            .setContentTitle("万能代理")
            .setContentText(statusText.ifEmpty { if (running) "运行中" else "未连接" })
            .setSmallIcon(android.R.drawable.ic_lock_idle_low_battery)
            .setContentIntent(pi)
            .setOngoing(running)
            .build()
    }

    private fun startCompatNotification(name: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(NotificationChannel("vpn", "代理连接", NotificationManager.IMPORTANCE_LOW))
        statusText = "连接中 · " + name
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, buildNotification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, buildNotification())
        }
    }

    private fun updateNotification() {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(1, buildNotification())
    }

    override fun onDestroy() { stopAll(); super.onDestroy() }
    override fun onRevoke() { stopAll() }
}
