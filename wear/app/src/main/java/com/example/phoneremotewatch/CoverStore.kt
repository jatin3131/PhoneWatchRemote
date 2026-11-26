package com.example.phoneremotewatch

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/** Covers kept on the watch: the last 25 played songs and up to 45 predicted ones. */
class CoverStore(context: Context) {

    companion object {
        const val MAX_PLAYED = 25
        const val MAX_PREDICTED = 45

        fun keyOf(title: String): String {
            var t = title.lowercase()
            t = t.replace(Regex("\\(.*?\\)|\\[.*?\\]"), " ")
            t = t.replace(Regex("\\b(feat|ft|featuring)\\b.*"), " ")
            return t.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }.joinToString("-")
        }
    }

    private val dir = File(context.filesDir, "covers")
    private val indexFile = File(dir, "index.txt")
    private val played = ArrayList<String>()
    private val predicted = ArrayList<String>()
    private val memory = object : LinkedHashMap<String, Bitmap>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean {
            return size > MAX_PLAYED
        }
    }

    init {
        dir.mkdirs()
        loadIndex()
    }

    private fun fileFor(key: String): File {
        val h = (key.hashCode().toLong() and 0xffffffffL).toString(16)
        return File(dir, h + "_" + key.length + ".jpg")
    }

    private fun loadIndex() {
        try {
            if (!indexFile.exists()) return
            for (line in indexFile.readLines()) {
                val p = line.split("|", limit = 2)
                if (p.size != 2) continue
                if (!fileFor(p[1]).exists()) continue
                if (p[0] == "P") played.add(p[1]) else if (p[0] == "N") predicted.add(p[1])
            }
        } catch (e: Exception) {
            // Start with an empty cache.
        }
    }

    private fun saveIndex() {
        try {
            val sb = StringBuilder()
            for (k in played) sb.append("P|").append(k).append('\n')
            for (k in predicted) sb.append("N|").append(k).append('\n')
            indexFile.writeText(sb.toString())
        } catch (e: Exception) {
            // Ignore storage problems.
        }
    }

    @Synchronized
    fun has(key: String): Boolean {
        return played.contains(key) || predicted.contains(key)
    }

    @Synchronized
    fun predictedCount(): Int = predicted.size

    /** Returns the saved cover (fast). A predicted cover that gets used moves to the played list. */
    @Synchronized
    fun get(key: String): Bitmap? {
        if (key.isEmpty()) return null
        val inPlayed = played.contains(key)
        val inPredicted = predicted.contains(key)
        if (!inPlayed && !inPredicted) return null
        var bmp = memory[key]
        if (bmp == null) {
            bmp = try {
                BitmapFactory.decodeFile(fileFor(key).absolutePath)
            } catch (e: Exception) {
                null
            }
        }
        if (bmp == null) {
            played.remove(key)
            predicted.remove(key)
            saveIndex()
            return null
        }
        memory[key] = bmp
        if (inPredicted) predicted.remove(key)
        played.remove(key)
        played.add(key)
        while (played.size > MAX_PLAYED) evictPlayed()
        saveIndex()
        return bmp
    }

    private fun evictPlayed() {
        val old = played.removeAt(0)
        memory.remove(old)
        if (!predicted.contains(old)) {
            try {
                fileFor(old).delete()
            } catch (e: Exception) {
                // Ignore.
            }
        }
    }

    @Synchronized
    fun putPlayed(key: String, bytes: ByteArray) {
        if (key.isEmpty() || bytes.isEmpty()) return
        try {
            fileFor(key).writeBytes(bytes)
        } catch (e: Exception) {
            return
        }
        memory.remove(key)
        predicted.remove(key)
        played.remove(key)
        played.add(key)
        while (played.size > MAX_PLAYED) evictPlayed()
        saveIndex()
    }

    @Synchronized
    fun putPredicted(key: String, bytes: ByteArray) {
        if (key.isEmpty() || bytes.isEmpty() || played.contains(key)) return
        try {
            fileFor(key).writeBytes(bytes)
        } catch (e: Exception) {
            return
        }
        predicted.remove(key)
        predicted.add(key)
        while (predicted.size > MAX_PREDICTED) {
            val old = predicted.removeAt(0)
            if (!played.contains(old)) {
                try {
                    fileFor(old).delete()
                } catch (e: Exception) {
                    // Ignore.
                }
            }
        }
        saveIndex()
    }
}
