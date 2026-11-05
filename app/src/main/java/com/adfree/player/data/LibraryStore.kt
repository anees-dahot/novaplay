package com.adfree.player.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Everything the library remembers about your videos: history, watched state,
 * favourites, playlists, hidden folders and the private (PIN-locked) list.
 * Read [version] in a composable to recompose when anything changes.
 */
object LibraryStore {
    private lateinit var prefs: SharedPreferences
    var version by mutableIntStateOf(0)
        private set

    /** PIN-locked list stays open until the app process ends. */
    var privateUnlocked = false

    fun init(context: Context) {
        if (!::prefs.isInitialized) prefs = context.applicationContext.getSharedPreferences("library", Context.MODE_PRIVATE)
    }

    private fun changed() { version++ }
    private fun set(key: String) = prefs.getStringSet(key, emptySet())!!.toSet()
    private fun putSet(key: String, v: Set<String>) { prefs.edit().putStringSet(key, v).apply(); changed() }
    private fun list(key: String): List<String> =
        prefs.getString(key, null)?.let { s -> JSONArray(s).let { a -> List(a.length()) { a.getString(it) } } } ?: emptyList()
    private fun putList(key: String, v: List<String>) { prefs.edit().putString(key, JSONArray(v).toString()).apply(); changed() }

    // ── History / watched
    /** Most recent first. */
    val recent: List<String> get() = list("recent")
    fun markPlayed(uri: String) = putList("recent", (listOf(uri) + recent.filter { it != uri }).take(200))
    fun removeRecent(uris: Collection<String>) = putList("recent", recent - uris.toSet())
    fun clearRecent() = putList("recent", emptyList())
    val played: Set<String> get() = set("played") + recent
    val watched: Set<String> get() = set("watched")
    fun markWatched(uri: String) {
        putSet("watched", watched + uri)
        putSet("played", set("played") + uri)
    }
    fun setWatched(uris: Collection<String>, value: Boolean) =
        putSet("watched", if (value) watched + uris else watched - uris.toSet())

    // ── Network streams the user opened
    val urls: List<String> get() = list("urls")
    fun addUrl(url: String) = putList("urls", (listOf(url) + urls.filter { it != url }).take(10))

    // ── Favourites
    val favourites: Set<String> get() = set("favourites")
    fun setFavourite(uris: Collection<String>, value: Boolean) =
        putSet("favourites", if (value) favourites + uris else favourites - uris.toSet())

    // ── Playlists (name -> ordered uris)
    val playlists: Map<String, List<String>>
        get() {
            val json = JSONObject(prefs.getString("playlists", "{}")!!)
            return json.keys().asSequence().associateWith { k ->
                json.getJSONArray(k).let { a -> List(a.length()) { a.getString(it) } }
            }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
        }
    private fun savePlaylists(map: Map<String, List<String>>) {
        val json = JSONObject()
        map.forEach { (k, v) -> json.put(k, JSONArray(v)) }
        prefs.edit().putString("playlists", json.toString()).apply()
        changed()
    }
    fun addToPlaylist(name: String, uris: Collection<String>) {
        val map = playlists.toMutableMap()
        map[name] = (map[name].orEmpty() + uris).distinct()
        savePlaylists(map)
    }
    fun removeFromPlaylist(name: String, uris: Collection<String>) {
        val map = playlists.toMutableMap()
        map[name] = map[name].orEmpty() - uris.toSet()
        savePlaylists(map)
    }
    fun deletePlaylist(name: String) = savePlaylists(playlists - name)

    // ── Hidden folders
    val hiddenFolders: Set<String> get() = set("hidden_folders")
    fun setFolderHidden(folder: String, hidden: Boolean) =
        putSet("hidden_folders", if (hidden) hiddenFolders + folder else hiddenFolders - folder)

    // ── Private videos (hidden from the library, shown only after PIN)
    val privateVideos: Set<String> get() = set("private")
    fun setPrivate(uris: Collection<String>, value: Boolean) =
        putSet("private", if (value) privateVideos + uris else privateVideos - uris.toSet())

    val hasPin: Boolean get() = prefs.contains("pin")
    fun setPin(pin: String) { prefs.edit().putString("pin", hash(pin)).apply(); changed() }
    fun checkPin(pin: String) = prefs.getString("pin", null) == hash(pin)
    private fun hash(pin: String) =
        MessageDigest.getInstance("SHA-256").digest("novaplay:$pin".toByteArray()).joinToString("") { "%02x".format(it) }

    // ── View preference
    var gridView: Boolean
        get() = prefs.getBoolean("grid", false)
        set(v) { prefs.edit().putBoolean("grid", v).apply(); changed() }

    var sortKey: String
        get() = prefs.getString("sort", "DATE")!!
        set(v) { prefs.edit().putString("sort", v).apply(); changed() }

    /** Forget a deleted/renamed video everywhere. */
    fun forget(uris: Collection<String>) {
        val gone = uris.toSet()
        prefs.edit()
            .putString("recent", JSONArray(recent - gone).toString())
            .putStringSet("watched", watched - gone)
            .putStringSet("favourites", favourites - gone)
            .putStringSet("private", privateVideos - gone)
            .apply()
        savePlaylists(playlists.mapValues { it.value - gone })
    }
}
