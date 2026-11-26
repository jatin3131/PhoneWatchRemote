package com.example.phoneremotewatch

import android.content.Context
import java.io.File

/** Remembers what you played, so we can guess what usually comes next. */
class PlayHistory(context: Context) {

    private val file = File(context.filesDir, "history.txt")
    private val items = ArrayList<Pair<String, String>>()

    init {
        try {
            if (file.exists()) {
                for (line in file.readLines()) {
                    val i = line.indexOf('\t')
                    if (i <= 0) continue
                    items.add(Pair(line.substring(0, i), line.substring(i + 1)))
                }
            }
        } catch (e: Exception) {
            // Start empty.
        }
    }

    private fun save() {
        try {
            val sb = StringBuilder()
            for (p in items) {
                sb.append(p.first.replace('\t', ' ').replace('\n', ' '))
                sb.append('\t')
                sb.append(p.second.replace('\t', ' ').replace('\n', ' '))
                sb.append('\n')
            }
            file.writeText(sb.toString())
        } catch (e: Exception) {
            // Ignore.
        }
    }

    @Synchronized
    fun add(title: String, artist: String) {
        val key = CoverStore.keyOf(title)
        if (key.isEmpty()) return
        if (items.isNotEmpty() && CoverStore.keyOf(items.last().first) == key) return
        items.add(Pair(title, artist))
        while (items.size > 400) items.removeAt(0)
        save()
    }

    /** Songs that you usually play right after this one, most frequent first. */
    @Synchronized
    fun followersOf(title: String, limit: Int): List<Pair<String, String>> {
        val key = CoverStore.keyOf(title)
        val counts = HashMap<String, Int>()
        val sample = HashMap<String, Pair<String, String>>()
        for (i in 0 until items.size - 1) {
            if (CoverStore.keyOf(items[i].first) != key) continue
            val next = items[i + 1]
            val nk = CoverStore.keyOf(next.first)
            if (nk.isEmpty() || nk == key) continue
            counts[nk] = (counts[nk] ?: 0) + 1
            sample[nk] = next
        }
        val result = ArrayList<Pair<String, String>>()
        for (e in counts.entries.sortedByDescending { it.value }) {
            val s = sample[e.key] ?: continue
            result.add(s)
            if (result.size >= limit) break
        }
        return result
    }
}
