package com.enzo.feed

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class SavedActivity : AppCompatActivity() {
    private val adapter = ArticleAdapter(
        { if (it.audio != null) openAudio(it) else openArticle(it) },
        { openAudio(it) },
        { Saved.toggle(it); refreshList() },
        { Saved.has(it) },
        true
    )
    private lateinit var empty: TextView
    private var query = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_saved)
        Saved.init(this)
        empty = findViewById(R.id.empty)
        val rv = findViewById<RecyclerView>(R.id.list)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<EditText>(R.id.search).doAfterTextChanged {
            query = it?.toString()?.trim() ?: ""
            refreshList()
        }
        refreshList()
    }

    private fun refreshList() {
        var l = Saved.list()
        if (query.isNotEmpty()) l = l.filter { it.source.contains(query, true) }
        adapter.submit(l)
        empty.visibility = if (l.isEmpty()) View.VISIBLE else View.GONE
    }
}
