package com.enzo.feed

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private val pool = Executors.newFixedThreadPool(6)
    private val adapter = ArticleAdapter(
        { if (it.audio != null) openAudio(it) else openArticle(it) },
        { openAudio(it) },
        { Saved.toggle(it) },
        { Saved.has(it) }
    )
    private lateinit var refresh: SwipeRefreshLayout
    private lateinit var list: RecyclerView
    private lateinit var empty: TextView
    private lateinit var tabs: LinearLayout
    private lateinit var tabPodcasts: ImageView
    private lateinit var btnNotify: ImageView
    private lateinit var store: FeedStore
    private var all = listOf<Article>()
    private var selected = "inicio"
    private var query = ""
    private var seenRev = -1
    @Volatile private var loadGen = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Saved.init(this)
        store = FeedStore(this)
        refresh = findViewById(R.id.refresh)
        list = findViewById(R.id.list)
        empty = findViewById(R.id.empty)
        tabs = findViewById(R.id.tabs)
        tabPodcasts = findViewById(R.id.tabPodcasts)
        btnNotify = findViewById(R.id.btnNotify)
        btnNotify.setOnClickListener { startActivity(Intent(this, NotificationsActivity::class.java)) }
        NotifyScheduler.ensure(this)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        refresh.setColorSchemeColors(getColor(R.color.accent))
        refresh.setProgressBackgroundColorSchemeColor(getColor(R.color.card))
        refresh.setOnRefreshListener { load() }
        findViewById<View>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<View>(R.id.btnSaved).setOnClickListener {
            startActivity(Intent(this, SavedActivity::class.java))
        }
        findViewById<EditText>(R.id.search).doAfterTextChanged {
            query = it?.toString()?.trim() ?: ""
            show(true)
        }
        tabPodcasts.setOnClickListener { select("podcasts") }
        all = Cache.load(this)
        buildTabs()
        show(true)
        load()
    }

    override fun onResume() {
        super.onResume()
        refreshBell()
        buildTabs()
        if (store.rev() != seenRev) load() else show(false)
    }

    private fun refreshBell() {
        val on = NotifyPrefs.active(this)
        btnNotify.setImageResource(if (on) R.drawable.ic_bell_filled else R.drawable.ic_bell_off)
        btnNotify.setColorFilter(if (on) Color.WHITE else getColor(R.color.muted))
    }

    private fun buildTabs() {
        tabs.removeAllViews()
        val enabled = store.themes().filter { it.on }
        if (selected != "podcasts" && enabled.none { it.id == selected }) {
            selected = enabled.firstOrNull()?.id ?: "podcasts"
        }
        for (t in enabled) {
            val tv = TextView(this)
            tv.text = Themes.name(t.id)
            tv.textSize = 14f
            tv.gravity = Gravity.CENTER
            tv.tag = t.id
            tv.setPadding(dp(16), 0, dp(16), 0)
            tv.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            tv.setOnClickListener { select(t.id) }
            tabs.addView(tv)
        }
        paintTabs()
    }

    private fun paintTabs() {
        for (i in 0 until tabs.childCount) {
            val v = tabs.getChildAt(i) as TextView
            val on = v.tag == selected
            v.setTextColor(getColor(if (on) R.color.accent else R.color.muted))
            v.setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
        }
        tabPodcasts.setColorFilter(getColor(if (selected == "podcasts") R.color.accent else R.color.muted))
    }

    private fun select(id: String) {
        selected = id
        paintTabs()
        show(true)
    }

    private fun show(scrollTop: Boolean) {
        var l = if (selected == "podcasts") all.filter { it.audio != null }
        else all.filter { it.audio == null && (selected == "inicio" || it.theme == selected) }
        if (query.isNotEmpty()) l = l.filter { it.source.contains(query, true) }
        adapter.submit(l)
        if (scrollTop) list.scrollToPosition(0)
        empty.visibility = if (l.isEmpty() && !refresh.isRefreshing) View.VISIBLE else View.GONE
    }

    /** Mostra cada feed assim que ele chega, sem esperar o mais lento. */
    private fun load() {
        seenRev = store.rev()
        val feeds = store.feeds()
        val gen = ++loadGen
        refresh.isRefreshing = true
        val titles = feeds.map { it.title }.toSet()
        val cached = all.filter { it.source in titles }
        val app = applicationContext
        Thread {
            val ecs = ExecutorCompletionService<Fetched?>(pool)
            feeds.forEach { f ->
                ecs.submit(Callable<Fetched?> { runCatching { Rss.fetch(f) }.getOrNull() })
            }
            val fresh = mutableListOf<Article>()
            val done = mutableSetOf<String>()
            repeat(feeds.size) {
                val r = ecs.take().get()
                if (r != null) {
                    fresh.addAll(r.articles)
                    done.add(r.feed.title)
                    if (r.feed.icon.isBlank() && r.cover.isNotBlank()) store.setIcon(r.feed.url, r.cover)
                    val merged = (fresh + cached.filter { it.source !in done }).sortedByDescending { it.time }
                    runOnUiThread { if (gen == loadGen) { all = merged; show(false) } }
                }
            }
            val finalList = (fresh + cached.filter { it.source !in done })
                .sortedByDescending { it.time }.take(400)
            Cache.save(app, finalList)
            runOnUiThread {
                if (gen == loadGen) { all = finalList; refresh.isRefreshing = false; show(false) }
            }
        }.start()
    }
}
