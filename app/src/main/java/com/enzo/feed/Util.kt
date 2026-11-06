package com.enzo.feed

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.net.Uri
import android.widget.ImageView
import android.widget.Toast
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestBuilder

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

fun Context.toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

fun Context.openArticle(a: Article) {
    val url = a.link.ifBlank { a.audio ?: return }
    try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    catch (e: ActivityNotFoundException) { toast("Nenhum app para abrir o link") }
}

fun Context.openAudio(a: Article) {
    val u = a.audio ?: return
    try { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(u), "audio/*")) }
    catch (e: ActivityNotFoundException) { openArticle(a) }
}

/** Carrega o ícone tentando cada endereço da lista (separada por "|") até um funcionar. */
fun loadIcon(v: ImageView, chain: String) {
    val urls = chain.split("|").filter { it.isNotBlank() }
    if (urls.isEmpty()) {
        Glide.with(v).clear(v)
        v.setImageDrawable(null)
        return
    }
    var req: RequestBuilder<Drawable>? = null
    for (u in urls.reversed()) {
        val r = Glide.with(v).load(u).circleCrop()
        req = if (req == null) r else r.error(req)
    }
    req!!.into(v)
}
