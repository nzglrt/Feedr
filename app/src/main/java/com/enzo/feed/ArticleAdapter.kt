package com.enzo.feed

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide

fun ago(t: Long): String {
    if (t == 0L) return ""
    val m = (System.currentTimeMillis() - t) / 60000
    return when {
        m < 1 -> "agora"
        m < 60 -> "${m}min"
        m < 1440 -> "${m / 60}h"
        else -> "${m / 1440}d"
    }
}

class ArticleAdapter(
    private val onOpen: (Article) -> Unit,
    private val onAudio: (Article) -> Unit,
    private val onPin: (Article) -> Unit,
    private val isPinned: (Article) -> Boolean,
    private val listIsRebuiltOnPin: Boolean = false
) : RecyclerView.Adapter<ArticleAdapter.VH>() {

    private var items = listOf<Article>()

    fun submit(list: List<Article>) { items = list; notifyDataSetChanged() }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val image: ImageView = v.findViewById(R.id.image)
        val icon: ImageView = v.findViewById(R.id.icon)
        val meta: TextView = v.findViewById(R.id.meta)
        val title: TextView = v.findViewById(R.id.title)
        val play: TextView = v.findViewById(R.id.play)
        val pin: ImageView = v.findViewById(R.id.pin)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_article, parent, false))

    override fun getItemCount() = items.size

    private fun paintPin(h: VH, a: Article) {
        if (isPinned(a)) {
            h.pin.setImageResource(R.drawable.ic_pin_filled)
            h.pin.setColorFilter(Color.WHITE)
        } else {
            h.pin.setImageResource(R.drawable.ic_pin_outline)
            h.pin.setColorFilter(h.itemView.context.getColor(R.color.muted))
        }
    }

    override fun onBindViewHolder(h: VH, i: Int) {
        val a = items[i]
        h.title.text = a.title
        val parts = mutableListOf<String>()
        if (a.source.isNotEmpty()) {
            val flag = Country.flag(a.country)
            parts.add(if (flag.isEmpty()) a.source else a.source + " " + flag)
        }
        val t = ago(a.time)
        if (t.isNotEmpty()) parts.add(t)
        h.meta.text = parts.joinToString(" • ", prefix = "• ")
        loadIcon(h.icon, a.icon)
        if (a.image != null) {
            h.image.visibility = View.VISIBLE
            Glide.with(h.image).load(a.image).centerCrop().into(h.image)
        } else {
            Glide.with(h.image).clear(h.image)
            h.image.visibility = View.GONE
        }
        h.play.visibility = if (a.audio != null) View.VISIBLE else View.GONE
        paintPin(h, a)
        h.itemView.setOnClickListener { onOpen(a) }
        h.play.setOnClickListener { onAudio(a) }
        h.pin.setOnClickListener {
            onPin(a)
            if (!listIsRebuiltOnPin) paintPin(h, a)
        }
    }
}
