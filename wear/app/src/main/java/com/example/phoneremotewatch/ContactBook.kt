package com.example.phoneremotewatch

import android.content.Context
import java.io.File

/** The phonebook copied from the iPhone, stored privately on the watch. */
class ContactBook(private val context: Context) {

    class Entry(val name: String, val number: String, val digits: String)

    var entries: List<Entry> = emptyList()
        private set

    private fun file(): File = File(context.filesDir, "contacts.txt")

    private fun parse(text: String): List<Entry> {
        val list = ArrayList<Entry>()
        for (line in text.split("\n")) {
            val tab = line.indexOf('\t')
            if (tab <= 0) continue
            val name = line.substring(0, tab).trim()
            val number = line.substring(tab + 1).trim()
            if (name.isEmpty() || number.isEmpty()) continue
            list.add(Entry(name, number, number.filter { it.isDigit() }))
        }
        list.sortBy { it.name.lowercase() }
        return list
    }

    fun load(): Int {
        try {
            val f = file()
            if (f.exists()) {
                entries = parse(f.readText())
            }
        } catch (e: Exception) {
            entries = emptyList()
        }
        return entries.size
    }

    fun replaceFrom(text: String): Int {
        entries = parse(text)
        try {
            file().writeText(text)
        } catch (e: Exception) {
            // Ignore storage problems.
        }
        return entries.size
    }

    fun clear() {
        entries = emptyList()
        try {
            file().delete()
        } catch (e: Exception) {
            // Ignore.
        }
    }

    /** If the text is a phone number, returns the matching contact's name. */
    fun displayName(raw: String): String {
        val digits = raw.filter { it.isDigit() }
        if (digits.length < 6) return raw
        val tail = digits.takeLast(7)
        for (e in entries) {
            if (e.digits.length >= 7 && e.digits.takeLast(7) == tail) {
                return e.name
            }
        }
        return raw
    }
}
