package com.example.phoneremotewatch

import java.util.concurrent.atomic.AtomicInteger

/**
 * Saves covers of songs you may play next. It waits until you have stayed on a song,
 * keeps the saved set while the genre is the same, and stops as soon as the song changes.
 */
class CoverPrefetcher(private val store: CoverStore, private val history: PlayHistory) {

    companion object {
        const val DWELL_MS = 15000L
    }

    private val generation = AtomicInteger(0)
    private var activeGenre = ""

    @Volatile
    var status = "idle"

    fun cancel() {
        generation.incrementAndGet()
    }

    fun start(title: String, artist: String, batteryLow: Boolean) {
        val gen = generation.incrementAndGet()
        if (batteryLow) {
            status = "paused (low battery)"
            return
        }
        Thread { run(gen, title, artist) }.start()
    }

    private fun alive(gen: Int): Boolean {
        return generation.get() == gen
    }

    private fun run(gen: Int, title: String, artist: String) {
        try {
            Thread.sleep(DWELL_MS)
            if (!alive(gen)) return
            val genre = ArtFetcher.genreOf(title, artist)
            if (!alive(gen)) return
            val sameGenre = genre.isEmpty() || activeGenre.isEmpty() || genre.equals(activeGenre, ignoreCase = true)
            if (store.predictedCount() >= 20 && sameGenre) {
                if (activeGenre.isEmpty() && genre.isNotEmpty()) activeGenre = genre
                status = "kept ($activeGenre)"
                return
            }
            status = "working"
            var added = 0
            val downloaded = HashMap<String, ByteArray?>()

            for ((t, a) in history.followersOf(title, 8)) {
                if (!alive(gen) || added >= 45) return
                val key = CoverStore.keyOf(t)
                if (key.isEmpty() || store.has(key)) continue
                val bytes = ArtFetcher.bytesFor(t, a)
                if (bytes != null) {
                    store.putPredicted(key, bytes)
                    added++
                }
                Thread.sleep(800)
            }
            if (artist.isNotEmpty() && alive(gen)) {
                added = fill(gen, ArtFetcher.artistTracks(artist, 60), added, downloaded)
            }
            if (genre.isNotEmpty() && added < 45 && alive(gen)) {
                added = fill(gen, ArtFetcher.genreTracks(genre, 60), added, downloaded)
            }
            if (alive(gen)) {
                if (genre.isNotEmpty()) activeGenre = genre
                status = "ready ($added new, $activeGenre)"
            }
        } catch (e: Exception) {
            status = "stopped"
        }
    }

    private fun fill(
        gen: Int,
        list: List<ArtFetcher.Candidate>,
        startCount: Int,
        downloaded: HashMap<String, ByteArray?>
    ): Int {
        var added = startCount
        for (c in list) {
            if (!alive(gen) || added >= 45) break
            val key = CoverStore.keyOf(c.title)
            if (key.isEmpty() || store.has(key)) continue
            var bytes = downloaded[c.coverUrl]
            if (bytes == null && !downloaded.containsKey(c.coverUrl)) {
                bytes = ArtFetcher.imageBytes(c.coverUrl)
                downloaded[c.coverUrl] = bytes
                Thread.sleep(150)
            }
            if (bytes != null) {
                store.putPredicted(key, bytes)
                added++
            }
        }
        return added
    }
}
