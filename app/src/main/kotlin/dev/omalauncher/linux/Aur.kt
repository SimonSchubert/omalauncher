package dev.omalauncher.linux

import android.util.Log
import dev.omalauncher.httpGet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder

/** Searches the AUR over its RPC API: from Android directly, no proot needed. */
object Aur {
    private const val TAG = "aur"
    private const val RPC = "https://aur.archlinux.org/rpc/v5/search/"

    /** Package name and description matches; the RPC rejects queries under two characters. */
    suspend fun search(query: String): List<Package> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        return withContext(Dispatchers.IO) {
            runCatching {
                val url = RPC + URLEncoder.encode(q, "UTF-8").replace("+", "%20") + "?by=name-desc"
                parse(String(httpGet(url, readTimeout = 15_000)))
            }.getOrElse {
                Log.w(TAG, "search failed", it)
                emptyList()
            }
        }
    }

    private fun parse(body: String): List<Package> {
        val json = JSONObject(body)
        if (json.optString("type") == "error") {
            Log.w(TAG, "rpc error: ${json.optString("error")}")
            return emptyList()
        }
        val results = json.getJSONArray("results")
        return (0 until results.length()).map { i ->
            val o = results.getJSONObject(i)
            Package(
                name = o.getString("Name"),
                version = o.optString("Version"),
                // Description is null for some packages.
                description = if (o.isNull("Description")) "" else o.getString("Description"),
                repo = AUR,
                installed = false,
            )
        }
    }
}

/** [Package.repo] for AUR results. */
const val AUR = "aur"
