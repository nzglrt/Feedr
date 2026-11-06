package com.enzo.feed

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.net.URL

class NotifyFeedAdapter(
    private val np: NotifyPrefs,
    private val onToggle: (Feed, Boolean) -> Unit
) : RecyclerView.Adapter<NotifyFeedAdapter.VH>() {
    private var items = listOf<Feed>()

    fun submit(l: List<Feed>) { items = l; notifyDataSetChanged() }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val icon: ImageView = v.findViewById(R.id.icon)
        val title: TextView = v.findViewById(R.id.title)
        val sub: TextView = v.findViewById(R.id.sub)
        val sw: SwitchCompat = v.findViewById(R.id.sw)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_notify_feed, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, i: Int) {
        val f = items[i]
        val host = runCatching { URL(f.url).host }.getOrDefault("")
        h.title.text = f.title
        val flag = Country.flag(f.country.ifBlank { Country.guess(listOf(host), "") })
        h.sub.text = listOf(host, flag).filter { it.isNotEmpty() }.joinToString(" • ")
        loadIcon(h.icon, Rss.iconChain(host, f.icon, f.podcast))
        h.sw.setOnCheckedChangeListener(null)
        h.sw.isChecked = np.feedOn(f.url)
        h.sw.setOnCheckedChangeListener { _, on -> np.setFeed(f.url, on); onToggle(f, on) }
        h.itemView.setOnClickListener { h.sw.toggle() }
    }
}

class NotificationsActivity : AppCompatActivity() {
    private lateinit var np: NotifyPrefs
    private lateinit var store: FeedStore
    private lateinit var adapter: NotifyFeedAdapter
    private lateinit var master: SwitchCompat
    private lateinit var intervalValue: TextView
    private lateinit var emptyFeeds: TextView
    private var query = ""

    private val masterListener = CompoundButton.OnCheckedChangeListener { _, on ->
        if (on) {
            val needs = Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
            if (needs) permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) else enable()
        } else {
            np.enabled = false
            NotifyScheduler.apply(this)
        }
    }

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) enable()
        else {
            setMaster(false)
            toast("Sem permissão, as notificações ficam desligadas. Libere nas configurações do Android.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_notifications)
        np = NotifyPrefs(this)
        store = FeedStore(this)
        master = findViewById(R.id.swMaster)
        intervalValue = findViewById(R.id.intervalValue)
        emptyFeeds = findViewById(R.id.emptyFeeds)
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        setMaster(np.enabled && NotifyPrefs.active(this))
        val quiet = findViewById<SwitchCompat>(R.id.swQuiet)
        quiet.isChecked = np.quiet
        quiet.setOnCheckedChangeListener { _, on -> np.quiet = on }
        findViewById<View>(R.id.rowInterval).setOnClickListener { pickInterval() }
        renderInterval()

        val rv = findViewById<RecyclerView>(R.id.rvFeeds)
        rv.layoutManager = LinearLayoutManager(this)
        adapter = NotifyFeedAdapter(np) { _, on -> if (on && np.enabled) NotifyScheduler.baselineNow(this) }
        rv.adapter = adapter
        findViewById<EditText>(R.id.search).doAfterTextChanged {
            query = it?.toString() ?: ""
            refresh()
        }
        findViewById<View>(R.id.btnAll).setOnClickListener { setAll(true) }
        findViewById<View>(R.id.btnNone).setOnClickListener { setAll(false) }
        refresh()
    }

    private fun setMaster(on: Boolean) {
        master.setOnCheckedChangeListener(null)
        master.isChecked = on
        master.setOnCheckedChangeListener(masterListener)
    }

    private fun enable() {
        np.enabled = true
        NotifyScheduler.apply(this)
        NotifyScheduler.baselineNow(this)
        if (store.feeds().none { np.feedOn(it.url) }) toast("Agora escolha de quais sites você quer receber avisos")
    }

    private fun filtered() = store.feeds().filter {
        query.isBlank() || it.title.contains(query, true) || it.url.contains(query, true)
    }

    private fun refresh() {
        val l = filtered()
        adapter.submit(l)
        emptyFeeds.visibility = if (l.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun setAll(on: Boolean) {
        np.setMany(filtered().map { it.url }, on)
        if (on && np.enabled) NotifyScheduler.baselineNow(this)
        refresh()
    }

    private fun renderInterval() {
        intervalValue.text = when (np.intervalMin) { 60 -> "1 hora"; 180 -> "3 horas"; else -> "6 horas" }
    }

    private fun pickInterval() {
        val mins = intArrayOf(60, 180, 360)
        AlertDialog.Builder(this).setTitle("Frequência do resumo")
            .setItems(arrayOf("A cada 1 hora", "A cada 3 horas", "A cada 6 horas")) { _, i ->
                np.intervalMin = mins[i]
                renderInterval()
                NotifyScheduler.apply(this)
            }.show()
    }
}
