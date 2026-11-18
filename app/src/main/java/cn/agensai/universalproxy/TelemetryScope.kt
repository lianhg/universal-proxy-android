package cn.agensai.universalproxy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** 与 Activity 生命周期无关的长驻 scope：轮询属于服务生命周期 */
object TelemetryScope {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
