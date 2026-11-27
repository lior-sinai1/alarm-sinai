package com.alarmsinai.data

import android.content.Context
import com.alarmsinai.BuildConfig
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Talks to server.js /cameras/*. Video is heavy, so the route is chosen per use:
 *   1. home LAN  (direct to the Mini PC, no tunnel, fastest)
 *   2. Tailscale (remote viewing, if an address was set in the app)
 *   3. ngrok     (same address as the alarm — works everywhere but has a monthly data cap)
 */
class CameraRepository(context: Context) {

    enum class Route(val label: String) { LAN("בבית"), TAILSCALE("Tailscale"), NGROK("ngrok (מרחוק)") }

    private val prefs = context.applicationContext.getSharedPreferences("alarm_prefs", Context.MODE_PRIVATE)

    var lanUrl: String
        get() = prefs.getString(KEY_LAN_URL, DEFAULT_LAN_URL) ?: DEFAULT_LAN_URL
        set(v) { prefs.edit().putString(KEY_LAN_URL, v.trim()).apply(); invalidateRoute() }

    var tailscaleUrl: String
        get() = prefs.getString(KEY_TAILSCALE_URL, "") ?: ""
        set(v) { prefs.edit().putString(KEY_TAILSCALE_URL, v.trim()).apply(); invalidateRoute() }

    private val ngrokUrl: String
        get() = prefs.getString("server_url", AlarmRepository.DEFAULT_URL) ?: AlarmRepository.DEFAULT_URL

    /** Authenticated client for API, images and video (also used by the player). */
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)      // config save restarts Frigate (~60 s)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("X-API-Key", BuildConfig.ALARM_API_KEY).build())
        }
        .build()

    private val probeClient: OkHttpClient = http.newBuilder()
        .connectTimeout(1500, TimeUnit.MILLISECONDS)
        .readTimeout(2500, TimeUnit.MILLISECONDS)
        .build()

    // serializeNulls: "zone": null must reach the server to remove a zone.
    private val gson: Gson = GsonBuilder().serializeNulls().create()

    @Volatile private var base: String? = null
    @Volatile var route: Route? = null; private set
    @Volatile private var routeCheckedAt = 0L
    private var api: CameraApi? = null
    private var apiBase: String? = null

    private fun invalidateRoute() { routeCheckedAt = 0L }

    private fun normalize(url: String): String? {
        val u = url.trim().trimEnd('/')
        if (u.isEmpty()) return null
        return if (u.startsWith("http://") || u.startsWith("https://")) u else "http://$u"
    }

    private fun healthy(url: String): Boolean = try {
        probeClient.newCall(Request.Builder().url("$url/health").build()).execute().use { it.isSuccessful }
    } catch (_: Exception) { false }

    /** Picks the best reachable route; cached for a minute. */
    suspend fun baseUrl(force: Boolean = false): String = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        base?.let { if (!force && now - routeCheckedAt < 60_000) return@withContext it }
        val lan = normalize(lanUrl)
        val ts = normalize(tailscaleUrl)
        val (lanOk, tsOk) = coroutineScope {
            val a = async { lan != null && healthy(lan) }
            val b = async { ts != null && healthy(ts) }
            a.await() to b.await()
        }
        val (u, r) = when {
            lanOk -> lan!! to Route.LAN
            tsOk  -> ts!! to Route.TAILSCALE
            else  -> ngrokUrl.trimEnd('/') to Route.NGROK
        }
        base = u; route = r; routeCheckedAt = now
        u
    }

    private suspend fun api(): CameraApi {
        val b = baseUrl()
        synchronized(this) {
            if (api == null || apiBase != b) {
                api = Retrofit.Builder()
                    .baseUrl("$b/")
                    .client(http)
                    .addConverterFactory(GsonConverterFactory.create(gson))
                    .build()
                    .create(CameraApi::class.java)
                apiBase = b
            }
            return api!!
        }
    }

    /** Runs a call; on network failure re-checks the route once (e.g. left the house). */
    private suspend fun <T> call(block: suspend (CameraApi) -> T): T = try {
        block(api())
    } catch (e: java.io.IOException) {
        baseUrl(force = true)
        block(api())
    }

    suspend fun overview() = call { it.overview() }
    suspend fun scan() = call { it.scan() }
    suspend fun saveSettings(body: Map<String, Any?>) = call { it.saveSettings(body) }
    suspend fun stopSiren() = call { it.stopSiren() }
    suspend fun addCamera(body: Map<String, Any?>) = call { it.addCamera(body) }
    suspend fun updateCamera(id: String, body: Map<String, Any?>) = call { it.updateCamera(id, body) }
    suspend fun deleteCamera(id: String) = call { it.deleteCamera(id) }
    suspend fun events(camera: String?, before: Double?) =
        call { it.events(camera, before?.let { b -> "%.3f".format(java.util.Locale.US, b) }) }
    suspend fun faces() = call { it.faces() }
    suspend fun deleteFace(name: String) = call { it.deleteFace(name) }
    suspend fun addFace(name: String, bytes: ByteArray, mime: String) =
        call { it.addFace(name, bytes.toRequestBody(mime.toMediaType())) }

    // ── Raw media ──────────────────────────────────────────────────────────────
    suspend fun snapshotUrl(cameraId: String, height: Int) = "${baseUrl()}/cameras/$cameraId/snapshot?h=$height"
    suspend fun liveUrl(cameraId: String, sub: Boolean) =
        "${baseUrl()}/cameras/$cameraId/live" + if (sub) "?quality=sub" else ""
    suspend fun eventThumbUrl(eventId: String) = "${baseUrl()}/cameras/events/$eventId/thumbnail"
    suspend fun eventSnapshotUrl(eventId: String) = "${baseUrl()}/cameras/events/$eventId/snapshot"
    suspend fun eventClipUrl(eventId: String) = "${baseUrl()}/cameras/events/$eventId/clip"

    suspend fun bytes(url: String): ByteArray = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url).build()).execute().use { res ->
            if (!res.isSuccessful) throw java.io.IOException("HTTP ${res.code}")
            res.body?.bytes() ?: throw java.io.IOException("empty body")
        }
    }

    companion object {
        const val DEFAULT_LAN_URL = "http://192.168.1.92:3000"
        private const val KEY_LAN_URL = "camera_lan_url"
        private const val KEY_TAILSCALE_URL = "camera_tailscale_url"

        /** Human-readable message from a failed call (server sends { error }). */
        fun errorText(e: Throwable): String {
            if (e is HttpException) {
                val raw = try { e.response()?.errorBody()?.string() } catch (_: Exception) { null }
                val msg = try { Gson().fromJson(raw, Map::class.java)?.get("error") as? String } catch (_: Exception) { null }
                return when (e.code()) {
                    401 -> "השרת דחה את מפתח הגישה (401)"
                    501 -> "זיהוי פנים לא זמין בשרת הזה"
                    503 -> msg ?: "השרת לא זמין (503)"
                    else -> msg ?: "שגיאת שרת ${e.code()}"
                }
            }
            return if (e is java.io.IOException) "אין חיבור לשרת" else (e.message ?: e.javaClass.simpleName)
        }
    }
}
