package com.example.phoneremotewatch

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/** Looks up album covers and genres straight from the web when the watch has internet. */
object ArtFetcher {

    class Candidate(val title: String, val artist: String, val coverUrl: String)

    private val genreCache = ConcurrentHashMap<String, String>()

    private fun tokens(s: String): Set<String> {
        var t = s.lowercase()
        t = t.replace(Regex("\\(.*?\\)|\\[.*?\\]"), " ")
        t = t.replace(Regex("\\b(feat|ft|featuring)\\b.*"), " ")
        return t.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }.toSet()
    }

    private fun score(wantTitle: Set<String>, wantArtist: Set<String>, title: String, artist: String): Double {
        val ct = tokens(title)
        if (wantTitle.isEmpty() || ct.isEmpty()) return 0.0
        val common = wantTitle.count { it in ct }.toDouble()
        val ts = common / wantTitle.size * 0.7 + common / ct.size * 0.3
        if (ts < 0.6) return 0.0
        var a = 0.0
        if (wantArtist.isNotEmpty()) {
            val ca = tokens(artist)
            if (ca.isNotEmpty()) {
                a = wantArtist.count { it in ca }.toDouble() / minOf(wantArtist.size, ca.size)
            }
            if (a == 0.0 && ts < 0.95) return 0.0
        }
        return ts * 2 + a + 0.01
    }

    private fun httpGet(url: String): ByteArray? {
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 4000
            c.readTimeout = 4000
            c.requestMethod = "GET"
            c.setRequestProperty("User-Agent", "PhoneRemote")
            if (c.responseCode != 200) {
                c.disconnect()
                return null
            }
            val data = c.inputStream.use { it.readBytes() }
            c.disconnect()
            data
        } catch (e: Exception) {
            null
        }
    }

    private fun image(url: String): Bitmap? {
        val bytes = httpGet(url) ?: return null
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        return Bitmap.createScaledBitmap(bmp, 160, 160, true)
    }

    fun toJpeg(bmp: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
        return out.toByteArray()
    }

    fun imageBytes(url: String): ByteArray? {
        val bmp = image(url) ?: return null
        return toJpeg(bmp)
    }

    private fun fromItunes(title: String, artist: String): Bitmap? {
        val q = URLEncoder.encode((artist + " " + title).trim(), "UTF-8")
        val body = httpGet("https://itunes.apple.com/search?term=$q&media=music&entity=song&limit=15") ?: return null
        val arr = JSONObject(String(body, Charsets.UTF_8)).optJSONArray("results") ?: return null
        val wt = tokens(title)
        val wa = tokens(artist)
        var bestUrl: String? = null
        var bestGenre = ""
        var best = 0.0
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val url = o.optString("artworkUrl100", "")
            if (url.isEmpty()) continue
            val s = score(wt, wa, o.optString("trackName", ""), o.optString("artistName", ""))
            if (s > best) {
                best = s
                bestUrl = url
                bestGenre = o.optString("primaryGenreName", "")
            }
        }
        val u = bestUrl ?: return null
        if (bestGenre.isNotEmpty()) genreCache[CoverStore.keyOf(title)] = bestGenre
        return image(u.replace("100x100", "300x300"))
    }

    private fun fromDeezer(title: String, artist: String): Bitmap? {
        val q = URLEncoder.encode((artist + " " + title).trim(), "UTF-8")
        val body = httpGet("https://api.deezer.com/search?q=$q&limit=15") ?: return null
        val arr = JSONObject(String(body, Charsets.UTF_8)).optJSONArray("data") ?: return null
        val wt = tokens(title)
        val wa = tokens(artist)
        var bestUrl: String? = null
        var best = 0.0
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val url = o.optJSONObject("album")?.optString("cover_medium", "") ?: ""
            if (url.isEmpty()) continue
            val by = o.optJSONObject("artist")?.optString("name", "") ?: ""
            val s = score(wt, wa, o.optString("title", ""), by)
            if (s > best) {
                best = s
                bestUrl = url
            }
        }
        val u = bestUrl ?: return null
        return image(u)
    }

    /** Cover for the song that is playing now. */
    fun fetch(title: String, artist: String, done: (Bitmap?) -> Unit) {
        Thread {
            var bmp: Bitmap? = null
            try {
                bmp = fromItunes(title, artist)
            } catch (e: Exception) {
                // Try the next source.
            }
            if (bmp == null) {
                try {
                    bmp = fromDeezer(title, artist)
                } catch (e: Exception) {
                    // No cover found.
                }
            }
            done(bmp)
        }.start()
    }

    /** Cover bytes for a predicted song. */
    fun bytesFor(title: String, artist: String): ByteArray? {
        var bmp: Bitmap? = null
        try {
            bmp = fromItunes(title, artist)
        } catch (e: Exception) {
            // Try the next source.
        }
        if (bmp == null) {
            try {
                bmp = fromDeezer(title, artist)
            } catch (e: Exception) {
                // Nothing found.
            }
        }
        return if (bmp == null) null else toJpeg(bmp)
    }

    /** Genre of a song: remembered, or one quick lookup. Empty if unknown. */
    fun genreOf(title: String, artist: String): String {
        val key = CoverStore.keyOf(title)
        val known = genreCache[key]
        if (known != null) return known
        try {
            val q = URLEncoder.encode((artist + " " + title).trim(), "UTF-8")
            val body = httpGet("https://itunes.apple.com/search?term=$q&media=music&entity=song&limit=8") ?: return ""
            val arr = JSONObject(String(body, Charsets.UTF_8)).optJSONArray("results") ?: return ""
            val wt = tokens(title)
            val wa = tokens(artist)
            var best = 0.0
            var genre = ""
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val s = score(wt, wa, o.optString("trackName", ""), o.optString("artistName", ""))
                if (s > best) {
                    best = s
                    genre = o.optString("primaryGenreName", "")
                }
            }
            if (genre.isNotEmpty()) genreCache[key] = genre
            return genre
        } catch (e: Exception) {
            return ""
        }
    }

    private fun searchList(query: String, limit: Int, byArtist: Boolean): List<Candidate> {
        val out = ArrayList<Candidate>()
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            val attr = if (byArtist) "&attribute=artistTerm" else ""
            val body = httpGet("https://itunes.apple.com/search?term=$q&media=music&entity=song$attr&limit=$limit") ?: return out
            val arr = JSONObject(String(body, Charsets.UTF_8)).optJSONArray("results") ?: return out
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val url = o.optString("artworkUrl100", "")
                if (url.isEmpty()) continue
                val name = o.optString("trackName", "")
                val genre = o.optString("primaryGenreName", "")
                if (genre.isNotEmpty()) genreCache[CoverStore.keyOf(name)] = genre
                out.add(Candidate(name, o.optString("artistName", ""), url.replace("100x100", "300x300")))
            }
        } catch (e: Exception) {
            // Return what we have.
        }
        return out
    }

    fun artistTracks(artist: String, limit: Int): List<Candidate> {
        return searchList(artist, limit, true)
    }

    fun genreTracks(genre: String, limit: Int): List<Candidate> {
        return searchList(genre, limit, false)
    }
}
