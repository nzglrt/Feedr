package com.enzo.feed

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

fun Article.key() = link.ifBlank { title + source }

fun Article.toJson(): JSONObject = JSONObject()
    .put("title", title).put("link", link).put("source", source).put("time", time)
    .put("audio", audio ?: "").put("image", image ?: "").put("cats", cats)
    .put("theme", theme).put("icon", icon).put("country", country)

fun articleFrom(o: JSONObject) = Article(
    o.getString("title"), o.optString("link"), o.optString("source"), o.optLong("time"),
    o.optString("audio").ifBlank { null }, o.optString("image").ifBlank { null },
    o.optString("cats"), o.optString("theme"), o.optString("icon"), o.optString("country")
)

/** Notícias salvas para ler depois (mais recente primeiro). */
object Saved {
    private var sp: SharedPreferences? = null
    private val items = mutableListOf<Article>()
    private val keys = HashSet<String>()

    fun init(ctx: Context) {
        if (sp != null) return
        val prefs = ctx.applicationContext.getSharedPreferences("saved", Context.MODE_PRIVATE)
        sp = prefs
        runCatching {
            val a = JSONArray(prefs.getString("items", "[]"))
            for (i in 0 until a.length()) {
                val x = articleFrom(a.getJSONObject(i))
                items.add(x); keys.add(x.key())
            }
        }
    }

    fun has(a: Article) = keys.contains(a.key())
    fun list(): List<Article> = items.toList()

    fun toggle(a: Article): Boolean {
        val k = a.key()
        val added = if (keys.remove(k)) {
            items.removeAll { it.key() == k }
            false
        } else {
            keys.add(k); items.add(0, a)
            true
        }
        val arr = JSONArray()
        items.forEach { arr.put(it.toJson()) }
        sp?.edit()?.putString("items", arr.toString())?.apply()
        return added
    }
}

/** Última lista carregada, para o app abrir já com notícias enquanto atualiza. */
object Cache {
    fun save(ctx: Context, list: List<Article>) {
        runCatching {
            val a = JSONArray()
            list.forEach { a.put(it.toJson()) }
            File(ctx.filesDir, "cache.json").writeText(a.toString())
        }
    }

    fun load(ctx: Context): List<Article> = runCatching {
        val f = File(ctx.filesDir, "cache.json")
        if (!f.exists()) emptyList()
        else {
            val a = JSONArray(f.readText())
            (0 until a.length()).map { articleFrom(a.getJSONObject(it)) }
        }
    }.getOrDefault(emptyList())
}
