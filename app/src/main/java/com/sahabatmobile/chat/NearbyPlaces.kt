package com.sahabatmobile.chat

import org.json.JSONArray
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class NearbyPlace(
    val name: String,
    val address: String,
    val lat: Double,
    val lon: Double,
    val distanceKm: Double,
    val rating: Double,
    val reviews: Int,
    val mapsUrl: String,
    val phone: String = "",
    val whatsapp: String = "",
    val openNow: Boolean = false,
    val openText: String = "",
    val placeId: String = ""
)

object NearbyPlaces {

    private const val TAG = "SM_NEARBY"
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"
    private val PHONE = Regex("""^(?:\+?62|0)8[\d\s\-()]{7,}$""")
    private val WA_LINK = Regex("""^https://wa\.me/""")
    private val OPEN_STATUS = Regex("""(?i)(buka|tutup).*(pukul|sekarang|sementara)""")

    fun search(query: String, lat: Double, lon: Double, limit: Int = 5): List<NearbyPlace> {
        val q = query.trim().ifEmpty { "dokter gigi" }
        val html = fetch(
            "https://www.google.com/maps/search/${URLEncoder.encode(q, "UTF-8")}/@$lat,$lon,14z?hl=id"
        )
        if (html.isBlank()) {
            android.util.Log.w(TAG, "halaman Maps kosong")
            return emptyList()
        }
        val href = Regex("/search\\?tbm=map[^\"\\s]+").find(html)?.value ?: run {
            android.util.Log.w(TAG, "link data Maps tidak ditemukan")
            return emptyList()
        }
        val body = fetch("https://www.google.com" + href.replace("&amp;", "&"))
        val start = body.indexOf('[')
        if (start < 0) {
            android.util.Log.w(TAG, "respons data Maps tidak valid")
            return emptyList()
        }
        val root = try {
            JSONArray(body.substring(start))
        } catch (e: Exception) {
            android.util.Log.w(TAG, "gagal parse JSON Maps: ${e.message}")
            return emptyList()
        }
        val found = mutableListOf<NearbyPlace>()
        collect(root, found, lat, lon)
        val unique = found.distinctBy { "${it.name}|${it.lat}|${it.lon}" }
        android.util.Log.i(TAG, "tempat ditemukan=${unique.size}")
        return unique.sortedBy { it.distanceKm }.take(limit)
    }

    private fun fetch(urlText: String): String {
        val conn = URL(urlText).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 15000
        conn.readTimeout = 20000
        conn.setRequestProperty("User-Agent", UA)
        conn.setRequestProperty("Accept-Language", "id-ID,id;q=0.9,en;q=0.8")
        return try {
            if (conn.responseCode != 200) return ""
            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "fetch gagal: ${e.message}")
            ""
        } finally {
            conn.disconnect()
        }
    }

    private fun collect(node: Any, out: MutableList<NearbyPlace>, lat: Double, lon: Double) {
        if (node !is JSONArray) return
        parsePlace(node, lat, lon)?.let { out.add(it) }
        for (i in 0 until node.length()) {
            val child = node.opt(i) ?: continue
            collect(child, out, lat, lon)
        }
    }

    private fun parsePlace(a: JSONArray, lat: Double, lon: Double): NearbyPlace? {
        if (a.length() < 19) return null
        val id = a.optString(10)
        if (!id.startsWith("0x")) return null
        val name = a.optString(11)
        if (name.isBlank()) return null
        val coord = a.optJSONArray(9) ?: return null
        val numbers = (0 until coord.length()).mapNotNull { i ->
            val v = coord.optDouble(i, Double.NaN)
            v.takeIf { !it.isNaN() }
        }
        if (numbers.size < 2) return null
        val pLat = numbers[0]
        val pLon = numbers[1]

        var address = a.optString(18)
        if (address.isBlank()) {
            val parts = a.optJSONArray(2)
            if (parts != null) {
                address = (0 until parts.length())
                    .mapNotNull { parts.optString(it).takeIf { s -> s.isNotBlank() } }
                    .joinToString(", ")
            }
        }
        if (address.isBlank()) address = name

        var rating = 0.0
        var reviews = 0
        val info = a.optJSONArray(4)
        if (info != null && info.length() >= 2) {
            val last = info.optDouble(info.length() - 1, Double.NaN)
            val before = info.optDouble(info.length() - 2, Double.NaN)
            if (!last.isNaN() && !before.isNaN() && before in 0.0..5.0) {
                rating = before
                reviews = last.toInt()
            }
        }

        val reviewUrl = findString(a, Regex("""placeid=ChIJ[A-Za-z0-9_-]+"""))
        val placeId = Regex("""placeid=(ChIJ[A-Za-z0-9_-]+)""")
            .find(reviewUrl)?.groupValues?.get(1) ?: ""
        val mapsUrl = if (placeId.isNotBlank()) {
            "https://www.google.com/maps/search/?api=1&query=" +
                URLEncoder.encode(name, "UTF-8") + "&query_place_id=$placeId"
        } else {
            "https://www.google.com/maps/search/?api=1&query=" +
                URLEncoder.encode("$pLat,$pLon", "UTF-8")
        }

        val phoneRaw = findString(a, PHONE)
        val phone = if (phoneRaw.filter { it.isDigit() }.length >= 9) phoneRaw else ""
        val waRaw = findString(a, WA_LINK)
        val whatsapp = when {
            waRaw.isNotBlank() -> waRaw
            phone.isNotBlank() -> "https://wa.me/62" + phone.filter { it.isDigit() }.trimStart('0')
            else -> ""
        }
        val openText = findString(a, OPEN_STATUS)

        return NearbyPlace(
            name = name,
            address = address,
            lat = pLat,
            lon = pLon,
            distanceKm = distanceKm(lat, lon, pLat, pLon),
            rating = rating,
            reviews = reviews,
            mapsUrl = mapsUrl,
            phone = phone,
            whatsapp = whatsapp,
            openNow = openText.trim().startsWith("Buka", true),
            openText = openText,
            placeId = placeId
        )
    }

    private fun findString(node: Any, regex: Regex): String {
        if (node !is JSONArray) return ""
        for (i in 0 until node.length()) {
            val v = node.opt(i) ?: continue
            if (v is String && regex.containsMatchIn(v)) return v
            val found = findString(v, regex)
            if (found.isNotBlank()) return found
        }
        return ""
    }

    private fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }
}
