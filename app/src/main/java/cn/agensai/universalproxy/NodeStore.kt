package cn.agensai.universalproxy

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 节点/订阅持久化（SharedPreferences，与桌面端 localStorage 同构语义） */
class NodeStore(context: Context) {
    private val sp = context.getSharedPreferences("upc_android", Context.MODE_PRIVATE)

    fun nodes(): JSONArray {
        val raw = sp.getString("nodes", "[]") ?: "[]"
        return try { JSONArray(raw) } catch (e: Exception) { JSONArray() }
    }

    fun saveNodes(arr: JSONArray) = sp.edit().putString("nodes", arr.toString()).apply()

    /** 合并导入：按 fingerprint 去重，不覆盖已有（与桌面端一致） */
    fun mergeNodes(imported: JSONArray): Int {
        val cur = nodes()
        val seen = HashSet<String>()
        for (i in 0 until cur.length()) seen.add(cur.optJSONObject(i)?.optString("fingerprint") ?: "")
        var added = 0
        for (i in 0 until imported.length()) {
            val n = imported.optJSONObject(i) ?: continue
            val fp = n.optString("fingerprint")
            if (fp.isNotEmpty() && seen.add(fp)) { cur.put(n); added++ }
        }
        saveNodes(cur)
        return added
    }

    fun subscriptions(): JSONArray {
        val raw = sp.getString("subs", "[]") ?: "[]"
        return try { JSONArray(raw) } catch (e: Exception) { JSONArray() }
    }

    fun saveSubscriptions(arr: JSONArray) = sp.edit().putString("subs", arr.toString()).apply()

    private fun settings(): JSONObject {
        val raw = sp.getString("settings", "{}") ?: "{}"
        return try { JSONObject(raw) } catch (e: Exception) { JSONObject() }
    }

    private fun putSettings(key: String, value: String) =
        sp.edit().putString("settings", settings().put(key, value).toString()).apply()

    fun mode(): String = settings().optString("mode", "bypass-private")
    fun setMode(mode: String) = putSettings("mode", mode)
    fun selected(): String = settings().optString("selected", "")
    fun setSelected(id: String) = putSettings("selected", id)
}
