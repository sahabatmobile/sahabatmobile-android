package com.sahabatmobile.chat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Penyimpanan "Papan Hasil AI" — kartu hasil AI yang di-pin pengguna dari chat.
 * Disimpan di SharedPreferences "sm_prefs" (key: papan_cards) sebagai JSON array.
 *
 * Aturan:
 *  - kartu baru masuk ke depan (paling baru di atas),
 *  - URL yang sama tidak disimpan dua kali,
 *  - maksimal [MAX_CARDS] kartu (yang paling lama dibuang otomatis).
 */
object PapanStore {

    private const val PREFS = "sm_prefs"
    private const val KEY = "papan_cards"
    private const val MAX_CARDS = 60

    const val TYPE_SHOPEE = "shopee"

    data class Card(
        val title: String,
        val url: String,
        val image: String? = null,
        val rating: Double = 0.0,
        val keyword: String = "",
        val type: String = TYPE_SHOPEE,
        val ts: Long = System.currentTimeMillis(),
        /** Harga siap tampil, mis. "Rp180.000". Kosong = belum diambil, "-" = tidak tersedia. */
        val price: String = "",
        /** Waktu pengambilan harga terakhir (untuk mencoba lagi setelah beberapa jam). */
        val priceTs: Long = 0L
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("type", type)
            .put("title", title)
            .put("url", url)
            .put("image", image ?: "")
            .put("rating", rating)
            .put("keyword", keyword)
            .put("ts", ts)
            .put("price", price)
            .put("priceTs", priceTs)

        companion object {
            /** Kompatibel dengan data lama (hanya title/url/image). */
            fun fromJson(o: JSONObject): Card = Card(
                title = o.optString("title"),
                url = o.optString("url"),
                image = o.optString("image").ifEmpty { null },
                rating = o.optDouble("rating", 0.0).let { if (it.isNaN()) 0.0 else it },
                keyword = o.optString("keyword"),
                type = o.optString("type", TYPE_SHOPEE),
                ts = o.optLong("ts"),
                price = o.optString("price"),
                priceTs = o.optLong("priceTs")
            )
        }
    }

    fun load(context: Context): List<Card> {
        val raw = prefs(context).getString(KEY, null) ?: return emptyList()
        val arr = try {
            JSONArray(raw)
        } catch (e: Exception) {
            return emptyList()
        }
        val out = mutableListOf<Card>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("url").isEmpty()) continue
            out.add(Card.fromJson(o))
        }
        return out
    }

    /**
     * Pin sekumpulan produk ke papan.
     * @return jumlah kartu baru yang benar-benar ditambahkan (0 = semua sudah ada).
     */
    fun pin(context: Context, cards: List<Card>): Int {
        if (cards.isEmpty()) return 0
        val current = load(context).toMutableList()
        val known = current.mapTo(mutableSetOf()) { keyOf(it.url) }
        var added = 0
        // Iterasi terbalik agar urutan hasil pencarian (1,2,3...) tetap terbaca di papan:
        // produk pertama dari pencarian menjadi paling atas.
        for (c in cards.asReversed()) {
            if (c.url.isEmpty() || !known.add(keyOf(c.url))) continue
            current.add(0, c)
            added++
        }
        if (added > 0) {
            while (current.size > MAX_CARDS) current.removeAt(current.size - 1)
            save(context, current)
        }
        return added
    }

    fun remove(context: Context, url: String) {
        val key = keyOf(url)
        val next = load(context).filterNot { keyOf(it.url) == key }
        save(context, next)
    }

    /** Simpan harga hasil render halaman produk ("-" = harga tidak ketemu). */
    fun setPrice(context: Context, url: String, price: String) {
        val key = keyOf(url)
        val cards = load(context)
        var changed = false
        val next = cards.map {
            if (keyOf(it.url) == key && (it.price != price || it.priceTs == 0L)) {
                changed = true
                it.copy(price = price, priceTs = System.currentTimeMillis())
            } else {
                it
            }
        }
        if (changed) save(context, next)
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY).apply()
    }

    fun count(context: Context): Int = load(context).size

    private fun save(context: Context, cards: List<Card>) {
        val arr = JSONArray()
        cards.forEach { arr.put(it.toJson()) }
        prefs(context).edit().putString(KEY, arr.toString()).apply()
    }

    /** Kunci duplikat: abaikan query & garis miring di akhir URL. */
    private fun keyOf(url: String): String =
        url.substringBefore('?').trimEnd('/').lowercase(java.util.Locale.ROOT)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
