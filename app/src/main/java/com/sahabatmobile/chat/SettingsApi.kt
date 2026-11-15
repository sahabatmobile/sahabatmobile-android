package com.sahabatmobile.chat

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Helper ringan untuk dipakai di layar Pengaturan (ProfileActivity).
 * Berisi operasi yang tidak memerlukan state Activity NativeChatActivity.
 */
object SettingsApi {

    const val API_BASE = "https://www.sahabatmobile.com/api/v1"

    data class Model(val id: String, val name: String, val inPlan: Boolean? = null)

    /** Ambil daftar model yang tersedia untuk API key tersebut. */
    fun fetchModels(apiKey: String): List<Model> {
        if (apiKey.isBlank()) return emptyList()
        return try {
            val conn = URL("$API_BASE/models").openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            val out = mutableListOf<Model>()
            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().readText()
                val data = JSONObject(body).optJSONArray("data") ?: JSONArray()
                for (i in 0 until data.length()) {
                    val o = data.optJSONObject(i) ?: continue
                    val id = o.optString("id")
                    if (id.isBlank()) continue
                    out.add(
                        Model(
                            id = id,
                            name = o.optString("name").ifEmpty { id },
                            inPlan = parsePlanFlag(o)
                        )
                    )
                }
            }
            conn.disconnect()
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Penanda "model ditanggung paket" yang dikirim server. */
    private fun parsePlanFlag(o: JSONObject): Boolean? {
        val truthy = listOf(
            "in_plan", "in_package", "plan_only", "package_only",
            "included_in_plan", "plan_included", "is_plan_model"
        )
        for (k in truthy) {
            if (o.has(k)) {
                val v = o.opt(k)
                if (v is Boolean) return v
                if (v is Number) return v.toInt() != 0
            }
        }
        val access = o.optString("access").lowercase()
        if (access == "paket" || access == "package" || access == "plan") return true
        if (access == "payg") return false
        val tags = o.optJSONArray("tags")
        if (tags != null) {
            for (i in 0 until tags.length()) {
                val t = tags.optString(i).lowercase()
                if (t == "paket" || t == "package" || t == "plan") return true
                if (t == "payg") return false
            }
        }
        return null
    }

    /**
     * Deteksi jenis API key: "paket", "payg", "invalid", atau "unknown".
     */
    fun probeKeyType(apiKey: String): String {
        if (apiKey.isBlank()) return "invalid"
        return try {
            val payload = JSONObject()
                .put("model", "mimo-v2.5")
                .put(
                    "messages",
                    JSONArray().put(JSONObject().put("role", "user").put("content", "hai"))
                )
                .put("max_tokens", 1)
            val conn = URL("$API_BASE/chat/completions").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.outputStream.use { it.write(payload.toString().toByteArray()) }

            val code = conn.responseCode
            var type = "unknown"
            var body: String? = null
            if (code == 200) {
                type = "paket"
            } else {
                body = conn.errorStream?.bufferedReader()?.readText()
            }
            if (code != 200 && body != null) {
                val errObj = JSONObject(body).optJSONObject("error")
                val errCode = errObj?.optString("code")
                when {
                    code == 401 -> type = "invalid"
                    errCode == "model_requires_package" -> type = "payg"
                    code == 403 -> type = "payg"
                    code == 402 -> type = "paket"
                    code == 429 -> type = "paket"
                }
            }
            conn.disconnect()
            type
        } catch (_: Exception) {
            "unknown"
        }
    }
}
