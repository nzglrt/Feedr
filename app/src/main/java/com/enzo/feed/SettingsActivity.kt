package com.enzo.feed

import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.net.URL
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class ThemeAdapter(
    private val items: MutableList<ThemePref>,
    private val onChange: () -> Unit,
    private val startDrag: (RecyclerView.ViewHolder) -> Unit,
    private val onLong: (ThemePref) -> Unit
) : RecyclerView.Adapter<ThemeAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val handle: TextView = v.findViewById(R.id.handle)
        val name: TextView = v.findViewById(R.id.name)
        val sw: SwitchCompat = v.findViewById(R.id.sw)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_theme, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, i: Int) {
        val t = items[i]
        h.name.text = Themes.name(h.itemView.context, t.id)
        h.sw.setOnCheckedChangeListener(null)
        h.sw.isChecked = t.on
        h.sw.setOnCheckedChangeListener { _, c -> t.on = c; onChange() }
        h.handle.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) startDrag(h)
            false
        }
        h.itemView.setOnLongClickListener {
            if (Themes.isCustom(t.id)) { onLong(t); true } else false
        }
    }
}

class FeedRowAdapter(private val onClick: (Feed) -> Unit) : RecyclerView.Adapter<FeedRowAdapter.VH>() {
    private var items = listOf<Feed>()

    fun submit(l: List<Feed>) { items = l; notifyDataSetChanged() }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val icon: ImageView = v.findViewById(R.id.icon)
        val title: TextView = v.findViewById(R.id.title)
        val sub: TextView = v.findViewById(R.id.sub)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_feed, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, i: Int) {
        val f = items[i]
        val ctx = h.itemView.context
        val host = runCatching { URL(f.url).host }.getOrDefault("")
        h.title.text = f.title
        val parts = mutableListOf(host)
        val flag = Country.flag(f.country.ifBlank { Country.guess(listOf(host), "") })
        if (flag.isNotEmpty()) parts.add(flag)
        if (f.podcast) parts.add(ctx.getString(R.string.podcast_label))
        if (f.theme != null) parts.add(ctx.getString(R.string.feed_sub_theme, Themes.name(ctx, f.theme)))
        h.sub.text = parts.joinToString(" • ")
        loadIcon(h.icon, Rss.iconChain(host, f.icon, f.podcast))
        h.itemView.setOnClickListener { onClick(f) }
    }
}

class SettingsActivity : AppCompatActivity() {
    private lateinit var store: FeedStore
    private lateinit var themes: MutableList<ThemePref>
    private lateinit var themeAdapter: ThemeAdapter
    private lateinit var feedAdapter: FeedRowAdapter
    private lateinit var emptyFeeds: TextView
    private lateinit var themesHeader: TextView
    private lateinit var themesBody: View
    private var query = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        store = FeedStore(this)
        themes = store.themes()
        emptyFeeds = findViewById(R.id.emptyFeeds)
        themesHeader = findViewById(R.id.themesHeader)
        themesBody = findViewById(R.id.themesBody)
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnAdd).setOnClickListener { addFeed() }
        findViewById<View>(R.id.btnNewTheme).setOnClickListener { newTheme() }

        findViewById<TextView>(R.id.languageValue).text =
            getString(if (Lang.code(this) == "en") R.string.lang_en else R.string.lang_pt)
        findViewById<View>(R.id.rowLanguage).setOnClickListener { pickLanguage() }

        themesHeader.setOnClickListener {
            themesBody.visibility = if (themesBody.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            renderHeader()
        }

        val rvThemes = findViewById<RecyclerView>(R.id.rvThemes)
        rvThemes.layoutManager = LinearLayoutManager(this)
        lateinit var helper: ItemTouchHelper
        themeAdapter = ThemeAdapter(
            themes,
            { store.saveThemes(themes); renderHeader() },
            { helper.startDrag(it) },
            { removeTheme(it) }
        )
        rvThemes.adapter = themeAdapter
        helper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(
                rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder
            ): Boolean {
                val a = vh.adapterPosition
                val b = target.adapterPosition
                Collections.swap(themes, a, b)
                themeAdapter.notifyItemMoved(a, b)
                return true
            }

            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}
            override fun isLongPressDragEnabled() = false
            override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(rv, vh)
                store.saveThemes(themes)
            }
        })
        helper.attachToRecyclerView(rvThemes)

        val rvFeeds = findViewById<RecyclerView>(R.id.rvFeeds)
        rvFeeds.layoutManager = LinearLayoutManager(this)
        feedAdapter = FeedRowAdapter { showOptions(it) }
        rvFeeds.adapter = feedAdapter
        findViewById<EditText>(R.id.search).doAfterTextChanged {
            query = it?.toString() ?: ""
            refreshFeeds()
        }
        renderHeader()
        refreshFeeds()
    }

    private fun pickLanguage() {
        AlertDialog.Builder(this).setTitle(R.string.language_label)
            .setItems(arrayOf(getString(R.string.lang_pt), getString(R.string.lang_en))) { _, i ->
                Lang.set(this, if (i == 0) "pt" else "en")
            }.show()
    }

    private fun renderHeader() {
        val open = themesBody.visibility == View.VISIBLE
        themesHeader.text = if (open) getString(R.string.themes_open)
        else getString(R.string.themes_closed, themes.count { it.on }, themes.size)
    }

    private fun reloadThemes() {
        themes.clear()
        themes.addAll(store.themes())
        themeAdapter.notifyDataSetChanged()
        renderHeader()
    }

    private fun newTheme() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val name = EditText(this).apply { hint = getString(R.string.new_theme_name_hint); setSingleLine() }
        val keys = EditText(this).apply { hint = getString(R.string.new_theme_keys_hint) }
        box.addView(name)
        box.addView(keys)
        AlertDialog.Builder(this).setTitle(R.string.new_theme_title).setView(box)
            .setPositiveButton(R.string.create) { _, _ ->
                val n = name.text.toString().trim()
                val k = keys.text.toString().split(",").map { it.trim() }.filter { it.isNotEmpty() }
                if (n.isNotEmpty() && k.isNotEmpty()) { store.addCustomTheme(n, k); reloadThemes() }
                else toast(getString(R.string.fill_theme))
            }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun removeTheme(t: ThemePref) {
        AlertDialog.Builder(this).setTitle(getString(R.string.delete_theme_q, Themes.name(this, t.id)))
            .setPositiveButton(R.string.delete) { _, _ -> store.removeCustomTheme(t.id); reloadThemes() }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun refreshFeeds() {
        val l = store.feeds().filter {
            query.isBlank() || it.title.contains(query, true) || it.url.contains(query, true)
        }
        feedAdapter.submit(l)
        emptyFeeds.visibility = if (l.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun addFeed() {
        val input = EditText(this).apply {
            hint = getString(R.string.add_feeds_hint)
            minLines = 4
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        AlertDialog.Builder(this).setTitle(R.string.add_feeds_title).setView(input)
            .setPositiveButton(R.string.add) { _, _ ->
                val urls = input.text.toString().split(Regex("[\\s,;]+"))
                    .map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                if (urls.isNotEmpty()) importFeeds(urls)
            }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun importFeeds(urls: List<String>) {
        toast(if (urls.size == 1) getString(R.string.searching_one) else getString(R.string.searching_many, urls.size))
        Thread {
            val pool = Executors.newFixedThreadPool(4)
            val tasks = urls.map { u -> Callable<Feed?> { runCatching { Rss.discover(u) }.getOrNull() } }
            val results = pool.invokeAll(tasks).map { runCatching { it.get() }.getOrNull() }
            pool.shutdown()
            val found = results.filterNotNull()
            val failed = urls.filterIndexed { i, _ -> results[i] == null }
            val added = store.addAll(found)
            runOnUiThread {
                refreshFeeds()
                when {
                    urls.size == 1 && found.isEmpty() -> toast(getString(R.string.feed_not_found))
                    urls.size == 1 && added == 0 -> toast(getString(R.string.feed_already))
                    urls.size == 1 -> toast(getString(R.string.feed_added, found[0].title))
                    failed.isEmpty() -> toast(
                        if (found.size > added) getString(R.string.feeds_added_dupes, added, found.size - added)
                        else getString(R.string.feeds_added_n, added)
                    )
                    else -> AlertDialog.Builder(this)
                        .setTitle(getString(R.string.import_result_title, added, urls.size))
                        .setMessage(getString(R.string.import_failed_msg, failed.joinToString("\n")))
                        .setPositiveButton(R.string.ok, null).show()
                }
            }
        }.start()
    }

    private fun showOptions(f: Feed) {
        val opts = mutableListOf(getString(R.string.opt_rename), getString(R.string.opt_country))
        val themeLabel = getString(R.string.opt_fixed_theme)
        if (!f.podcast) opts.add(themeLabel)
        val removeLabel = getString(R.string.opt_remove)
        opts.add(removeLabel)
        AlertDialog.Builder(this).setTitle(f.title)
            .setItems(opts.toTypedArray()) { _, i ->
                when (opts[i]) {
                    getString(R.string.opt_rename) -> rename(f)
                    getString(R.string.opt_country) -> pickCountry(f)
                    themeLabel -> pickTheme(f)
                    else -> AlertDialog.Builder(this).setTitle(getString(R.string.remove_q, f.title))
                        .setPositiveButton(R.string.opt_remove) { _, _ -> store.remove(f); refreshFeeds() }
                        .setNegativeButton(R.string.cancel, null).show()
                }
            }.show()
    }

    private fun rename(f: Feed) {
        val input = EditText(this).apply { setText(f.title); setSingleLine() }
        AlertDialog.Builder(this).setTitle(R.string.rename_title).setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val n = input.text.toString().trim()
                if (n.isNotEmpty()) { store.update(f.copy(title = n)); refreshFeeds() }
            }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun pickCountry(f: Feed) {
        val names = (listOf(getString(R.string.automatic)) +
            Country.list.map { Country.flag(it.first) + "  " + Country.displayName(this, it.first) }).toTypedArray()
        AlertDialog.Builder(this).setTitle(getString(R.string.country_of, f.title))
            .setItems(names) { _, i ->
                store.update(f.copy(country = if (i == 0) "" else Country.list[i - 1].first))
                refreshFeeds()
            }.show()
    }

    private fun pickTheme(f: Feed) {
        val list = Themes.all().filter { it.id != "inicio" }
        val names = (listOf(getString(R.string.automatic_by_topic)) + list.map { Themes.name(this, it.id) }).toTypedArray()
        AlertDialog.Builder(this).setTitle(getString(R.string.theme_of, f.title))
            .setItems(names) { _, i ->
                store.update(f.copy(theme = if (i == 0) null else list[i - 1].id))
                refreshFeeds()
            }.show()
    }
}
