package com.enzo.feed

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Preferências de notificação e a lista do que já foi avisado. */
class NotifyPrefs(ctx: Context) {
    private val app = ctx.applicationContext
    private val sp = app.getSharedPreferences("notify", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = sp.getBoolean("enabled", false)
        set(v) { sp.edit().putBoolean("enabled", v).apply() }

    var intervalMin: Int
        get() = sp.getInt("interval", 60)
        set(v) { sp.edit().putInt("interval", v).apply() }

    var quiet: Boolean
        get() = sp.getBoolean("quiet", true)
        set(v) { sp.edit().putBoolean("quiet", v).apply() }

    private fun readSet(key: String): MutableSet<String> {
        val out = LinkedHashSet<String>()
        runCatching {
            val a = JSONArray(sp.getString(key, "[]"))
            for (i in 0 until a.length()) out.add(a.getString(i))
        }
        return out
    }

    private fun writeSet(key: String, s: Collection<String>) {
        sp.edit().putString(key, JSONArray(s.toList()).toString()).apply()
    }

    fun feedOn(url: String) = readSet("feeds").contains(url)

    fun setFeed(url: String, on: Boolean) = setMany(listOf(url), on)

    fun setMany(urls: Collection<String>, on: Boolean) {
        val s = readSet("feeds")
        if (on) s.addAll(urls) else s.removeAll(urls.toSet())
        writeSet("feeds", s)
    }

    fun baselined(): MutableSet<String> = readSet("baselined")
    fun saveBaselined(s: Collection<String>) = writeSet("baselined", s)

    private fun seenFile() = File(app.filesDir, "seen.json")

    fun loadSeen(): LinkedHashSet<String> {
        val out = LinkedHashSet<String>()
        runCatching {
            val f = seenFile()
            if (f.exists()) {
                val a = JSONArray(f.readText())
                for (i in 0 until a.length()) out.add(a.getString(i))
            }
        }
        return out
    }

    fun saveSeen(s: LinkedHashSet<String>) {
        while (s.size > 1500) { val it = s.iterator(); it.next(); it.remove() }
        runCatching { seenFile().writeText(JSONArray(s.toList()).toString()) }
    }

    companion object {
        /** Sininho "ativo": ligado no app e permitido pelo Android. */
        fun active(ctx: Context) =
            NotifyPrefs(ctx).enabled && NotificationManagerCompat.from(ctx).areNotificationsEnabled()
    }
}

object NotifyScheduler {
    private const val PERIODIC = "feedr-notify"
    private const val BASELINE = "feedr-baseline"

    private fun net() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    private fun enqueue(ctx: Context, policy: ExistingPeriodicWorkPolicy) {
        val np = NotifyPrefs(ctx)
        val req = PeriodicWorkRequest.Builder(
            NotifyWorker::class.java, np.intervalMin.toLong().coerceAtLeast(15), TimeUnit.MINUTES
        ).setConstraints(net()).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(PERIODIC, policy, req)
    }

    /** Chame ao mudar qualquer configuração: liga, atualiza ou cancela a verificação. */
    fun apply(ctx: Context) {
        if (NotifyPrefs(ctx).enabled) enqueue(ctx, ExistingPeriodicWorkPolicy.UPDATE)
        else WorkManager.getInstance(ctx).cancelUniqueWork(PERIODIC)
    }

    /** Garante que a verificação está agendada (chamado ao abrir o app). */
    fun ensure(ctx: Context) {
        if (NotifyPrefs(ctx).enabled) enqueue(ctx, ExistingPeriodicWorkPolicy.KEEP)
    }

    /** Só registra o que já existe nos sites recém-ligados, sem avisar. */
    fun baselineNow(ctx: Context) {
        val req = OneTimeWorkRequest.Builder(NotifyWorker::class.java)
            .setInputData(Data.Builder().putBoolean("baseline", true).build())
            .setConstraints(net()).build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(BASELINE, ExistingWorkPolicy.REPLACE, req)
    }
}

class NotifyWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        synchronized(LOCK) { run() }
        return Result.success()
    }

    private fun run() {
        val ctx = applicationContext
        val np = NotifyPrefs(ctx)
        if (!np.enabled) return
        val baselineOnly = inputData.getBoolean("baseline", false)
        val baselined = np.baselined()
        val feeds = FeedStore(ctx).feeds().filter { np.feedOn(it.url) }
        val targets = if (baselineOnly) feeds.filter { it.url !in baselined } else feeds
        if (targets.isEmpty()) return

        val pool = Executors.newFixedThreadPool(4)
        val results = try {
            pool.invokeAll(targets.map { f ->
                Callable<Fetched?> { runCatching { Rss.fetch(f) }.getOrNull() }
            }).map { runCatching { it.get() }.getOrNull() }
        } finally { pool.shutdown() }

        val seen = np.loadSeen()
        val fresh = mutableListOf<Article>()
        for ((i, f) in targets.withIndex()) {
            val r = results[i] ?: continue
            if (f.url !in baselined) {
                r.articles.forEach { seen.add(it.key()) }
                baselined.add(f.url)
            } else if (!baselineOnly) {
                fresh.addAll(r.articles.filter { it.key() !in seen })
            }
        }
        if (!baselineOnly && !(np.quiet && quietNow())) {
            fresh.forEach { seen.add(it.key()) }
            if (fresh.isNotEmpty()) notifyDigest(ctx, fresh)
        }
        np.saveSeen(seen)
        np.saveBaselined(baselined)
    }

    private fun quietNow(): Boolean {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return h >= 22 || h < 7
    }

    private fun fetchImages(arts: List<Article>): List<Bitmap?> {
        val pool = Executors.newFixedThreadPool(4)
        return try {
            pool.invokeAll(arts.map { a ->
                Callable<Bitmap?> { a.image?.let { u -> runCatching { download(u) }.getOrNull() } }
            }).map { runCatching { it.get() }.getOrNull() }
        } finally { pool.shutdown() }
    }

    /** Baixa a foto e reduz para no máximo 720 px de largura (limite seguro para notificações). */
    private fun download(url: String): Bitmap? {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 6000
        c.readTimeout = 8000
        c.setRequestProperty("User-Agent", "Feedr/0.5")
        try {
            if (c.responseCode !in 200..299) return null
            val bytes = c.inputStream.use { it.readBytes() }
            if (bytes.size > 8_000_000) return null
            val o = BitmapFactory.Options()
            o.inJustDecodeBounds = true
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            var sample = 1
            while (o.outWidth / sample > 1440) sample *= 2
            val o2 = BitmapFactory.Options()
            o2.inSampleSize = sample
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o2) ?: return null
            if (bmp.width <= 720) return bmp
            val h = bmp.height * 720 / bmp.width
            return Bitmap.createScaledBitmap(bmp, 720, h, true)
        } finally { c.disconnect() }
    }

    private fun thumb(b: Bitmap): Bitmap {
        val side = minOf(b.width, b.height)
        val sq = Bitmap.createBitmap(b, (b.width - side) / 2, (b.height - side) / 2, side, side)
        return Bitmap.createScaledBitmap(sq, 192, 192, true)
    }

    @SuppressLint("MissingPermission")
    private fun notifyDigest(ctx: Context, items: List<Article>) {
        val nm = NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL, "Resumo de notícias", NotificationManager.IMPORTANCE_DEFAULT)
            ch.description = "Resumo das novidades dos sites que você escolheu"
            ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
        val list = items.distinctBy { it.key() }.sortedByDescending { it.time }
        val n = list.size
        val shown = list.take(MAX_CHILDREN)
        val bitmaps = fetchImages(shown)
        val accent = ctx.getColor(R.color.accent)
        val openApp = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        fun openIntent(a: Article): PendingIntent {
            val url = a.link.ifBlank { a.audio ?: "" }
            val i = if (url.isNotBlank())
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            else openApp
            return PendingIntent.getActivity(
                ctx, a.key().hashCode(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

        // Um cartão por manchete: ao puxar, mostra a foto grande e a manchete.
        fun card(a: Article, bmp: Bitmap?): NotificationCompat.Builder {
            val b = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_feedr)
                .setColor(accent)
                .setContentTitle(a.source)
                .setContentText(a.title)
                .setContentIntent(openIntent(a))
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
            if (bmp != null) {
                b.setLargeIcon(thumb(bmp))
                b.setStyle(
                    NotificationCompat.BigPictureStyle().bigPicture(bmp)
                        .bigLargeIcon(null as Bitmap?).setSummaryText(a.title)
                )
            } else {
                b.setStyle(NotificationCompat.BigTextStyle().bigText(a.title))
            }
            return b
        }

        if (n == 1) {
            for (k in 1..MAX_CHILDREN) nm.cancel(CHILD_BASE + k)
            nm.notify(NOTIF_ID, card(list[0], bitmaps[0]).build())
            return
        }

        shown.forEachIndexed { i, a ->
            nm.notify(
                CHILD_BASE + i + 1,
                card(a, bitmaps[i]).setGroup(GROUP)
                    .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY).build()
            )
        }
        for (k in shown.size + 1..MAX_CHILDREN) nm.cancel(CHILD_BASE + k)

        // Resumo do grupo: é ele que avisa (vibra) uma vez só.
        val sources = list.map { it.source }.distinct()
        val title = "$n novas • " + sources.take(3).joinToString(", ") + if (sources.size > 3) "…" else ""
        val style = NotificationCompat.InboxStyle()
        list.take(5).forEach { style.addLine(it.source + " • " + it.title) }
        if (n > 5) style.setSummaryText("+${n - 5} mais")
        val appPi = PendingIntent.getActivity(
            ctx, 0, openApp, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        nm.notify(
            NOTIF_ID,
            NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_feedr)
                .setColor(accent)
                .setContentTitle(title)
                .setContentText(list[0].title)
                .setStyle(style)
                .setContentIntent(appPi)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setGroup(GROUP)
                .setGroupSummary(true)
                .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
                .build()
        )
    }

    companion object {
        private val LOCK = Any()
        private const val CHANNEL = "news"
        private const val NOTIF_ID = 1001
        private const val CHILD_BASE = 2000
        private const val MAX_CHILDREN = 5
        private const val GROUP = "feedr_news"
    }
}
