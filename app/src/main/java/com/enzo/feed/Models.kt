package com.enzo.feed

import android.content.Context
import android.util.Xml
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Locale

data class Feed(
    val url: String, val title: String, val podcast: Boolean = false,
    val theme: String? = null, val country: String = "", val icon: String = ""
)

data class Article(
    val title: String, val link: String, val source: String, val time: Long,
    val audio: String?, val image: String?, val cats: String,
    val theme: String = "", val icon: String = "", val country: String = ""
)

data class ThemePref(val id: String, var on: Boolean)
data class Parsed(val title: String, val link: String, val cover: String, val lang: String, val articles: List<Article>)
data class Fetched(val feed: Feed, val articles: List<Article>, val cover: String)

object Themes {
    class T(val id: String, val name: String, val keys: List<String>, val raw: List<String> = keys)

    val builtin = listOf(
        T("inicio", "Início", emptyList()),
        T("politica", "Política", listOf("politic", "eleic", "governo", "congresso", "senado", "camara dos", "stf", "bolsonaro", "lula", "ministro", "deputad", "partido", "planalto", "politics", "election", "parliament", "government", "minister", "white house", "congress")),
        T("economia", "Economia", listOf("econom", "dolar", "inflacao", "juros", "bolsa de valores", "ibovespa", " pib ", "imposto", "financ", "selic", "emprego", "negocios", "inflation", "stocks", "business", " gdp ", "wall street")),
        T("mundo", "Mundo", listOf("mundo", "internacional", "guerra", " eua ", "estados unidos", "china", "russia", "ucrania", "israel", "europa", "trump", " onu ", "gaza", "world", "war", "ukraine", "middle east", "europe")),
        T("tecnologia", "Tecnologia", listOf("tecnolog", "tech", " app ", "celular", "smartphone", "iphone", "android", "google", "apple", "microsoft", "inteligencia artificial", "chatgpt", "software", "internet", "starlink", "anatel", "processador", "notebook", "samsung", "whatsapp", " ai ", "artificial intelligence", "cyber", "startup")),
        T("ciencia", "Ciência", listOf("ciencia", "pesquisa", "cientista", "fossil", "descobert", "dinossauro", "science", "scientist", "research", " study ", "biolog", "fisica", "quimica", "genetic")),
        T("nasa", "NASA", listOf(" nasa ", "artemis", "james webb", "spacex", "foguete", "astronaut", " marte ", "estacao espacial", "hubble", "espaco", "astronom", "telescopio", "cometa", "eclipse", " lua ", "rocket", "space", " esa ", "isro")),
        T("papers", "Papers", listOf(" paper ", " papers ", "arxiv", "preprint", " journal ", "peer review", "biorxiv", "pubmed", "sciencedirect", "nature com", "plos", "artigo cientifico", "estudo publicado")),
        T("saude", "Saúde", listOf("saude", "vacina", "doenca", "hospital", "anvisa", "medic", "cancer", "dengue", "covid", " sus ", "health", "disease", "vaccine")),
        T("esportes", "Esportes", listOf("esporte", "futebol", "campeonato", " copa ", "flamengo", "corinthians", "palmeiras", "brasileirao", "olimpiad", " nba ", "tenis", "volei", "selecao", " ge ", "espn", "sport", "football", "soccer", "basketball", "cricket", " ufc ", "premier league")),
        T("f1", "F1", listOf("formula 1", " f1 ", "formula um", "grande premio", "verstappen", "hamilton", "leclerc", "norris", "ferrari", "mclaren", "interlagos", "motorsport", "formula1", "red bull racing")),
        T("esports", "E-sports", listOf("esports", "e sports", "league of legends", " lol ", "valorant", " cs2 ", "counter strike", " furia ", "dota", "free fire", "cblol", "rainbow six", "hltv", "cs go")),
        T("entretenimento", "Entretenimento", listOf("entretenimento", "pop arte", "famos", "cantor", "filme", "serie", "novela", "musica", " show ", " bbb ", "celebridade", "netflix", "cinema", " tv ", "entertainment", " film ", "music", "celebrity", "culture")),
        T("games", "Games", listOf("games", " game ", "jogo", "playstation", "xbox", "nintendo", "steam", " ps5 ", "fortnite", "minecraft", "gaming", "videogame")),
        T("educacao", "Educação", listOf("educacao", " enem ", "escola", "universidade", "vestibular", "professor", "estudante", "ensino", " mec ", "education", "school", "university")),
        T("meioambiente", "Meio ambiente", listOf("meio ambiente", "clima", "amazonia", "desmatamento", "queimada", "sustentab", "aquecimento global", "poluicao", "ambiental", " cop30 ", "climate", "environment", "wildlife"))
    )

    var custom: List<T> = emptyList()

    fun all() = builtin + custom
    fun isCustom(id: String) = id.startsWith("c_")
    fun name(id: String) = all().firstOrNull { it.id == id }?.name ?: id

    private fun norm(s: String) = " " + Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        .replace(Regex("[^a-z0-9]+"), " ") + " "

    fun makeCustom(id: String, name: String, raw: List<String>) =
        T(id, name, raw.map { norm(it).trim() }.filter { it.isNotEmpty() }, raw)

    /** Devolve o id do tema ou "" se não souber. Site/caminho/categorias pesam 3; título pesa 1. */
    fun classify(title: String, link: String, cats: String): String {
        val u = runCatching { URL(link) }.getOrNull()
        val path = norm((u?.host ?: "") + " " + (u?.path ?: "") + " " + cats)
        val t = norm(title)
        var best = ""; var bestScore = 0
        for (th in all()) {
            if (th.keys.isEmpty()) continue
            var s = 0
            for (k in th.keys) {
                if (path.contains(k)) s += 3
                if (t.contains(k)) s += 1
            }
            if (s > bestScore) { bestScore = s; best = th.id }
        }
        return best
    }
}

object Country {
    private val domains = mapOf(
        "bbc.co.uk" to "GB", "bbc.com" to "GB", "theguardian.com" to "GB", "reuters.com" to "GB",
        "nature.com" to "GB", "formula1.com" to "GB", "aljazeera.com" to "QA", "aljazeera.net" to "QA",
        "nytimes.com" to "US", "cnn.com" to "US", "nasa.gov" to "US", "npr.org" to "US",
        "washingtonpost.com" to "US", "wired.com" to "US", "theverge.com" to "US", "techcrunch.com" to "US",
        "arstechnica.com" to "US", "space.com" to "US", "arxiv.org" to "US", "science.org" to "US",
        "espn.com" to "US", "dw.com" to "DE", "france24.com" to "FR", "rt.com" to "RU",
        "globo.com" to "BR", "tecnoblog.net" to "BR", "hltv.org" to "DK", "esa.int" to "EU"
    )
    private val tlds = mapOf(
        "br" to "BR", "uk" to "GB", "de" to "DE", "fr" to "FR", "es" to "ES", "pt" to "PT", "it" to "IT",
        "nl" to "NL", "ch" to "CH", "se" to "SE", "no" to "NO", "ru" to "RU", "ua" to "UA", "pl" to "PL",
        "tr" to "TR", "il" to "IL", "qa" to "QA", "ae" to "AE", "sa" to "SA", "eg" to "EG", "in" to "IN",
        "cn" to "CN", "jp" to "JP", "kr" to "KR", "au" to "AU", "ca" to "CA", "mx" to "MX", "ar" to "AR",
        "cl" to "CL", "za" to "ZA", "gov" to "US", "edu" to "US", "eu" to "EU", "ie" to "IE", "be" to "BE",
        "at" to "AT", "dk" to "DK", "fi" to "FI", "nz" to "NZ"
    )

    val list = listOf(
        "BR" to "Brasil", "US" to "Estados Unidos", "GB" to "Reino Unido", "PT" to "Portugal", "ES" to "Espanha",
        "FR" to "França", "DE" to "Alemanha", "IT" to "Itália", "NL" to "Países Baixos", "CH" to "Suíça",
        "SE" to "Suécia", "NO" to "Noruega", "DK" to "Dinamarca", "IE" to "Irlanda", "RU" to "Rússia",
        "UA" to "Ucrânia", "PL" to "Polônia", "TR" to "Turquia", "IL" to "Israel", "QA" to "Catar",
        "AE" to "Emirados Árabes", "SA" to "Arábia Saudita", "EG" to "Egito", "IN" to "Índia", "CN" to "China",
        "JP" to "Japão", "KR" to "Coreia do Sul", "AU" to "Austrália", "CA" to "Canadá", "MX" to "México",
        "AR" to "Argentina", "CL" to "Chile", "ZA" to "África do Sul", "EU" to "União Europeia"
    )

    fun guess(hosts: List<String>, lang: String): String {
        for (h in hosts) {
            val d = h.lowercase()
            for ((k, v) in domains) if (d == k || d.endsWith(".$k")) return v
        }
        for (h in hosts) {
            val tld = h.substringAfterLast('.').lowercase()
            val c = tlds[tld]
            if (c != null) return c
        }
        val l = lang.lowercase().replace('_', '-')
        if (l.contains('-')) {
            val r = l.substringAfter('-').take(2).uppercase()
            if (r.length == 2 && r.all { it in 'A'..'Z' }) return r
        }
        if (l == "pt") return "BR"
        return ""
    }

    fun flag(code: String): String {
        if (code.length != 2 || !code.all { it in 'A'..'Z' }) return ""
        return String(Character.toChars(0x1F1E6 + (code[0] - 'A'))) +
            String(Character.toChars(0x1F1E6 + (code[1] - 'A')))
    }
}

class FeedStore(ctx: Context) {
    private val sp = ctx.getSharedPreferences("feed", Context.MODE_PRIVATE)

    init { Themes.custom = loadCustom() }

    fun rev() = sp.getInt("rev", 0)
    private fun touch() { sp.edit().putInt("rev", rev() + 1).apply() }

    fun feeds(): List<Feed> {
        val raw = sp.getString("feeds", null) ?: return defaults().also { save(it) }
        val arr = JSONArray(raw)
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Feed(
                o.getString("url"), o.getString("title"),
                o.optBoolean("podcast", false),
                o.optString("theme", "").ifBlank { null },
                o.optString("country", ""), o.optString("icon", "")
            )
        }
    }

    private fun save(list: List<Feed>, bump: Boolean = true) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject().put("url", it.url).put("title", it.title)
                    .put("podcast", it.podcast).put("theme", it.theme ?: "")
                    .put("country", it.country).put("icon", it.icon)
            )
        }
        sp.edit().putString("feeds", arr.toString()).apply()
        if (bump) touch()
    }

    fun add(f: Feed) { val l = feeds(); if (l.none { it.url == f.url }) save(l + f) }
    fun addAll(fs: List<Feed>): Int {
        val l = feeds().toMutableList()
        var n = 0
        for (f in fs) if (l.none { it.url == f.url }) { l.add(f); n++ }
        if (n > 0) save(l)
        return n
    }

    fun remove(f: Feed) = save(feeds().filter { it.url != f.url })
    fun update(f: Feed) = save(feeds().map { if (it.url == f.url) f else it })
    fun setIcon(url: String, icon: String) = save(feeds().map { if (it.url == url) it.copy(icon = icon) else it }, false)

    fun themes(): MutableList<ThemePref> {
        val out = mutableListOf<ThemePref>()
        val raw = sp.getString("themes", null)
        if (raw != null) {
            val a = JSONArray(raw)
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val id = o.getString("id")
                if (Themes.all().any { it.id == id }) out.add(ThemePref(id, o.optBoolean("on", true)))
            }
        }
        for (t in Themes.all()) if (out.none { it.id == t.id }) out.add(ThemePref(t.id, true))
        return out
    }

    /** Ordem e visibilidade dos temas: só afeta as abas, não precisa baixar os feeds de novo. */
    fun saveThemes(l: List<ThemePref>) {
        val arr = JSONArray()
        l.forEach { arr.put(JSONObject().put("id", it.id).put("on", it.on)) }
        sp.edit().putString("themes", arr.toString()).apply()
    }

    private fun loadCustom(): List<Themes.T> {
        val raw = sp.getString("custom", null) ?: return emptyList()
        val a = JSONArray(raw)
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            val ks = o.getJSONArray("keys")
            Themes.makeCustom(o.getString("id"), o.getString("name"), (0 until ks.length()).map { i -> ks.getString(i) })
        }
    }

    private fun saveCustom(l: List<Themes.T>) {
        Themes.custom = l
        val arr = JSONArray()
        l.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name).put("keys", JSONArray(it.raw))) }
        sp.edit().putString("custom", arr.toString()).apply()
        touch()
    }

    fun addCustomTheme(name: String, keys: List<String>) =
        saveCustom(Themes.custom + Themes.makeCustom("c_${System.currentTimeMillis()}", name, keys))

    fun removeCustomTheme(id: String) = saveCustom(Themes.custom.filter { it.id != id })

    private fun defaults() = listOf(
        Feed("https://g1.globo.com/rss/g1/", "G1"),
        Feed("https://tecnoblog.net/feed/", "Tecnoblog", false, "tecnologia")
    )
}

object Rss {
    private val fmts = listOf(
        "EEE, dd MMM yyyy HH:mm:ss Z", "EEE, dd MMM yyyy HH:mm:ss zzz",
        "yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss.SSSXXX"
    )
    private val audioExt = Regex(".*\\.(mp3|m4a|aac|ogg|opus|wav)$", RegexOption.IGNORE_CASE)
    private val imgRegex = Regex("<img[^>]+src=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
    private const val LIMIT = 40

    fun favicon(host: String) = "https://www.google.com/s2/favicons?domain=$host&sz=64"

    /** Lista de endereços de ícone separados por "|": o app tenta um por um até um carregar. */
    fun iconChain(host: String, cover: String, podcast: Boolean): String {
        val s2 = favicon(host)
        val ddg = "https://icons.duckduckgo.com/ip3/$host.ico"
        val ico = "https://$host/favicon.ico"
        val l = if (podcast && cover.isNotEmpty()) listOf(cover, s2, ddg, ico) else listOf(s2, ddg, ico, cover)
        return if (host.isEmpty()) cover else l.filter { it.isNotEmpty() }.joinToString("|")
    }

    private fun parseDate(s: String): Long {
        val t = s.trim()
        for (f in fmts) {
            try { return SimpleDateFormat(f, Locale.US).parse(t)!!.time } catch (e: Exception) { }
        }
        return 0L
    }

    private fun fix(u: String?): String {
        var s = u?.trim() ?: return ""
        if (s.startsWith("//")) s = "https:$s"
        if (s.startsWith("http://")) s = "https://" + s.substring(7)
        return s
    }

    private fun open(start: String): HttpURLConnection {
        var u = start
        for (i in 0 until 5) {
            val c = URL(u).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 6000
            c.readTimeout = 8000
            c.setRequestProperty("User-Agent", "Feedr/0.3")
            val code = c.responseCode
            if (code in 300..399) {
                u = URL(URL(u), c.getHeaderField("Location")).toString()
                c.disconnect()
                continue
            }
            if (code >= 400) { c.disconnect(); throw IOException("HTTP $code") }
            return c
        }
        throw IOException("Redirecionamentos demais")
    }

    fun get(url: String): String {
        val c = open(url)
        try { return c.inputStream.bufferedReader().use { it.readText() } }
        finally { c.disconnect() }
    }

    /** Lê o feed em fluxo e para depois de LIMIT itens (feeds de podcast podem ter milhares). */
    fun fetch(f: Feed): Fetched {
        val c = open(f.url)
        try {
            val xp = Xml.newPullParser()
            xp.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            xp.setInput(c.inputStream, null)
            val p = parse(xp, f.title, LIMIT)
            val feedHost = runCatching { URL(f.url).host }.getOrDefault("")
            val siteHost = runCatching { URL(p.link).host }.getOrDefault("").ifBlank { feedHost }
            val icon = iconChain(siteHost, f.icon.ifBlank { p.cover }, f.podcast)
            val country = f.country.ifBlank { Country.guess(listOf(siteHost, feedHost), p.lang) }
            val list = p.articles.map { a ->
                a.copy(
                    theme = if (a.audio != null) "podcast" else (f.theme ?: Themes.classify(a.title, a.link, a.cats)),
                    icon = icon, country = country
                )
            }
            return Fetched(f, list, p.cover)
        } finally { c.disconnect() }
    }

    private fun looksLikeFeed(b: String) = b.take(2000).let {
        it.contains("<rss", true) || it.contains("<feed", true) || it.contains("<rdf", true)
    }

    private fun shortTitle(s: String) = s.split(Regex("\\s[-–—|•:]\\s")).first().trim()

    private fun build(url: String, body: String): Feed {
        val p = parseString(body, "")
        val pod = p.articles.isNotEmpty() && p.articles.count { it.audio != null } * 2 >= p.articles.size
        val feedHost = runCatching { URL(url).host }.getOrDefault("")
        val siteHost = runCatching { URL(p.link).host }.getOrDefault("").ifBlank { feedHost }
        return Feed(
            url, shortTitle(p.title).ifBlank { feedHost }, pod, null,
            Country.guess(listOf(siteHost, feedHost), p.lang), p.cover
        )
    }

    fun discover(input: String): Feed? {
        var url = input.trim()
        if (!url.startsWith("http")) url = "https://$url"
        val body = get(url)
        if (looksLikeFeed(body)) return build(url, body)
        val tag = Regex("<link[^>]+type=[\"']application/(?:rss|atom)\\+xml[\"'][^>]*>", RegexOption.IGNORE_CASE)
            .find(body) ?: return null
        val href = Regex("href=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
            .find(tag.value)?.groupValues?.get(1) ?: return null
        val feedUrl = URL(URL(url), href.replace("&amp;", "&")).toString()
        val b2 = get(feedUrl)
        if (!looksLikeFeed(b2)) return null
        return build(feedUrl, b2)
    }

    private fun readAll(p: XmlPullParser): String {
        var depth = 1
        val sb = StringBuilder()
        while (depth > 0) {
            when (p.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> sb.append(p.text)
                XmlPullParser.END_DOCUMENT -> return sb.toString()
            }
        }
        return sb.toString()
    }

    fun parseString(xml: String, source: String): Parsed {
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(StringReader(xml))
        return parse(p, source, Int.MAX_VALUE)
    }

    fun parse(p: XmlPullParser, source: String, limit: Int): Parsed {
        var feedTitle = ""; var feedLink = ""; var cover = ""; var lang = ""; var inImage = false
        val out = mutableListOf<Article>()
        var inItem = false
        var title = ""; var link = ""; var date = ""
        var audio: String? = null; var image: String? = null
        var cats = StringBuilder()
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                val n = p.name
                if (n == "item" || n == "entry") {
                    inItem = true; title = ""; link = ""; date = ""
                    audio = null; image = null; cats = StringBuilder()
                } else if (!inItem) {
                    when (n) {
                        "image" -> inImage = true
                        "title" -> if (feedTitle.isEmpty() && !inImage) feedTitle = p.nextText().trim()
                        "language" -> if (lang.isEmpty()) lang = p.nextText().trim()
                        "link" -> if (feedLink.isEmpty()) {
                            val href = p.getAttributeValue(null, "href")
                            val rel = p.getAttributeValue(null, "rel")
                            if (href != null) { if (rel == null || rel == "alternate") feedLink = href }
                            else feedLink = p.nextText().trim()
                        }
                        "itunes:image" -> { val h = fix(p.getAttributeValue(null, "href")); if (h.isNotEmpty()) cover = h }
                        "url" -> if (inImage && cover.isEmpty()) cover = fix(p.nextText())
                    }
                } else when (n) {
                    "title" -> title = p.nextText().trim()
                    "link" -> {
                        val href = p.getAttributeValue(null, "href")
                        val rel = p.getAttributeValue(null, "rel")
                        if (href != null) { if (rel == null || rel == "alternate") link = href }
                        else link = p.nextText().trim()
                    }
                    "pubDate", "published", "updated", "dc:date" -> { val d = p.nextText(); if (date.isEmpty()) date = d }
                    "category" -> {
                        val t = p.getAttributeValue(null, "term")
                        cats.append(' ').append(t ?: p.nextText())
                    }
                    "enclosure" -> {
                        val t = p.getAttributeValue(null, "type") ?: ""
                        val u = p.getAttributeValue(null, "url")
                        if (u != null) {
                            if (t.startsWith("audio") || audioExt.matches(u.substringBefore('?'))) audio = u
                            else if (t.startsWith("image") && image == null) image = fix(u)
                        }
                    }
                    "media:content", "media:thumbnail" -> {
                        val u = p.getAttributeValue(null, "url")
                        val t = (p.getAttributeValue(null, "type") ?: "") + (p.getAttributeValue(null, "medium") ?: "")
                        if (u != null && image == null && !t.contains("audio") && !t.contains("video") &&
                            !audioExt.matches(u.substringBefore('?'))) image = fix(u)
                    }
                    "itunes:image" -> { val h = p.getAttributeValue(null, "href"); if (h != null && image == null) image = fix(h) }
                    "description", "summary", "content:encoded", "content" -> {
                        val html = readAll(p)
                        if (image == null) {
                            val m = imgRegex.find(html)
                            if (m != null) image = fix(m.groupValues[1])
                        }
                    }
                }
            } else if (ev == XmlPullParser.END_TAG) {
                if (p.name == "image") inImage = false
                else if (inItem && (p.name == "item" || p.name == "entry")) {
                    if (title.isNotBlank()) {
                        val img = image ?: (if (audio != null && cover.isNotEmpty()) cover else null)
                        out.add(Article(title, link.ifBlank { audio ?: "" }, source, parseDate(date), audio, img, cats.toString()))
                    }
                    inItem = false
                    if (out.size >= limit) break
                }
            }
            ev = p.next()
        }
        return Parsed(feedTitle, feedLink, cover, lang, out)
    }
}
