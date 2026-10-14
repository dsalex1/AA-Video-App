package de.purlife.akademieauto

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * Where each video was left off (like YouTube's "continue watching"): players start there again (see
 * [Web.MEDIA_JS]), and the last unfinished video can be reopened with the continue buttons.
 */
object Progress {
    data class Entry(val key: String, val positionS: Double, val pageUrl: String, val title: String)

    private const val MIN_S = 10.0 // less isn't worth continuing
    private const val END_S = 20.0 // this close to the end counts as finished (credits, outro)
    private const val LAST = "last"

    private lateinit var prefs: SharedPreferences
    private val listeners = mutableSetOf<() -> Unit>()

    fun init(context: Context) {
        if (!::prefs.isInitialized) prefs = context.applicationContext.getSharedPreferences("progress", Context.MODE_PRIVATE)
    }

    fun addListener(listener: () -> Unit) = listeners.add(listener)

    fun removeListener(listener: () -> Unit) = listeners.remove(listener)

    /** Where [key]'s video continues, or 0. */
    fun positionS(key: String): Double = entry(key)?.positionS ?: 0.0

    /** The most recently watched video that isn't finished. */
    val last: Entry? get() = prefs.getString(LAST, null)?.let(::entry)

    /** There is an unfinished video, and it isn't the one playing right now. */
    fun isResumable(): Boolean {
        val last = last ?: return false
        val now = MediaHub.current
        return now == null || now.key != last.key || !now.playing
    }

    /** Records [state] of a video shown on [pageUrl]; returns true when it just finished. */
    fun update(state: MediaHub.State, pageUrl: String?): Boolean {
        if (state.key.isEmpty() || state.durationMs <= 0) return false
        val position = state.positionMs / 1000.0
        val duration = state.durationMs / 1000.0
        if (state.ended || position >= duration - END_S) {
            if (!prefs.contains(state.key)) return false
            prefs.edit().remove(state.key).apply { if (prefs.getString(LAST, null) == state.key) remove(LAST) }.apply()
            notifyListeners()
            return true
        }
        if (position < MIN_S || pageUrl == null) return false
        val isNew = prefs.getString(LAST, null) != state.key
        val json = JSONObject().put("p", position).put("u", pageUrl).put("t", state.title).toString()
        prefs.edit().putString(state.key, json).putString(LAST, state.key).apply()
        if (isNew) notifyListeners()
        return false
    }

    private fun entry(key: String): Entry? {
        val o = prefs.getString(key, null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        return Entry(key, o.optDouble("p"), o.optString("u"), o.optString("t"))
    }

    private fun notifyListeners() = listeners.toList().forEach { it() }
}
