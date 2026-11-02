package com.pelisld.app

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.StringReader
import java.util.concurrent.TimeUnit

/** Entrada reproducible (la usa M3uParser). */
data class PlayItem(val title: String, val url: String, val group: String = "")

/** Un canal; [urls] guarda las fuentes alternativas (de otras listas) por si una se cae. */
data class Ch(
    val id: String,          // URL del canal: identifica cada entrada de forma única
    val key: String,         // nombre normalizado: sirve para favoritos y para buscar fuentes alternativas
    val name: String,
    val group: String,
    val urls: MutableList<String>,
    val src: Int             // número de la lista de donde viene
) {
    var dup: Boolean = false // true si hay otro canal con el mismo nombre en alguna lista
}

/** Listas, canales guardados (caché), favoritos y ajustes. */
object Store {

    /**
     * Listas de origen (nombre del archivo verificado en GitHub -> URL original).
     * GitHub las revisa cada día y publica en /listas solo los canales que funcionan.
     */
    private class Src(val name: String, val original: String, val fallback: Boolean = true)

    private const val PLUTO = "https://raw.githubusercontent.com/BuddyChewChew/app-m3u-generator/main/playlists/"

    private val SOURCES = listOf(
        Src("mexico_lg", "https://raw.githubusercontent.com/Llorchico/LG/refs/heads/main/mxlg.m3u"),
        Src("espanol", "https://iptv-org.github.io/iptv/languages/spa.m3u"),
        Src("peliculas", "https://iptv-org.github.io/iptv/categories/movies.m3u"),
        Src("portugues", "https://iptv-org.github.io/iptv/languages/por.m3u"),
        Src("brasil_m3ucl", "https://m3u.cl/lista/BR.m3u"),
        Src("clasicos", "https://iptv-org.github.io/iptv/categories/classic.m3u"),
        // Estas son listas generales: GitHub deja solo las películas, por eso no tienen lista de respaldo.
        Src("free_tv", "https://raw.githubusercontent.com/Free-TV/IPTV/master/playlist.m3u8", false),
        Src("pluto_mx", PLUTO + "plutotv_mx.m3u", false),
        Src("pluto_ar", PLUTO + "plutotv_ar.m3u", false),
        Src("pluto_cl", PLUTO + "plutotv_cl.m3u", false),
        Src("plex_mx", PLUTO + "plex_mx.m3u", false)
    )

    private fun verified(name: String): String? =
        if (BuildConfig.LISTS_BASE.isEmpty()) null else BuildConfig.LISTS_BASE + name + ".m3u"

    /** Listas que vienen puestas al instalar la app: las verificadas en GitHub (o las originales si no hay repo). */
    val DEFAULT_URLS: List<String> = SOURCES.map { verified(it.name) ?: it.original }

    /** Si la lista verificada aún no existe o no responde, se usa la original. */
    private val FALLBACK: Map<String, String> =
        SOURCES.filter { it.fallback }.mapNotNull { s -> verified(s.name)?.let { it to s.original } }.toMap()

    private val ORIGINAL_URLS: List<String> = SOURCES.map { it.original }

    /** Lista inicial (México): de ella se muestran solo las películas si el filtro está activo. */
    val DEFAULT_URL: String = DEFAULT_URLS[0]

    /** Versión de las listas por defecto: al subirla, las instalaciones existentes reciben las nuevas (una sola vez). */
    private const val DEFAULTS_VERSION = 5

    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private fun p(c: Context) = c.getSharedPreferences("tv", Context.MODE_PRIVATE)

    // ---- Listas ----
    /** Listas incluidas en la app (carpeta assets). Se agregan como listas locales, una sola vez por versión. */
    private val BUNDLED: Map<Int, List<String>> = emptyMap()

    /** Lista que traían versiones anteriores de la app; se quita de las instalaciones existentes. */
    private const val REMOVED_LOCAL = "local://Globo-SBT-BR.m3u"

    private fun seedBundled(c: Context) {
        val seen = p(c).getInt("bundledV", 0)
        val latest = BUNDLED.keys.maxOrNull() ?: return
        if (seen >= latest) return
        val raw = p(c).getString("lists", null)
        val current = try {
            if (raw == null) DEFAULT_URLS.toMutableList()
            else JSONArray(raw).let { a -> (0 until a.length()).map { a.getString(it) }.toMutableList() }
        } catch (e: Exception) { DEFAULT_URLS.toMutableList() }
        for ((v, names) in BUNDLED) if (v > seen) for (name in names) {
            val text = runCatching { c.assets.open(name).bufferedReader().use { it.readText() } }.getOrNull() ?: continue
            val url = LOCAL_PREFIX + name
            runCatching { cacheFile(c, url).writeText(text) }
            if (url !in current) current.add(url)
        }
        saveLists(c, current)
        p(c).edit().putInt("bundledV", latest).apply()
    }

    fun lists(c: Context): List<String> {
        seedBundled(c)
        val raw = p(c).getString("lists", null) ?: return DEFAULT_URLS
        val saved = try {
            val a = JSONArray(raw)
            (0 until a.length()).map { a.getString(it) }
        } catch (e: Exception) {
            return DEFAULT_URLS
        }
        // Migración: agrega las listas por defecto nuevas una sola vez (si las quitas, no vuelven)
        if (p(c).getInt("defaultsV", 1) < DEFAULTS_VERSION) {
            // v3/v4: las listas originales se reemplazan por las verificadas en GitHub
            val clean = saved.filter { it != REMOVED_LOCAL }
            if (clean.size != saved.size) {
                cacheFile(c, REMOVED_LOCAL).delete()
                setListEnabled(c, REMOVED_LOCAL, true)
            }
            val kept = if (FALLBACK.isNotEmpty()) clean.filter { it !in ORIGINAL_URLS } else clean
            val merged = kept + DEFAULT_URLS.filter { it !in kept }
            saveLists(c, merged)
            p(c).edit().putInt("defaultsV", DEFAULTS_VERSION).apply()
            return merged
        }
        return saved
    }

    private fun saveLists(c: Context, l: List<String>) {
        p(c).edit().putString("lists", JSONArray(l).toString()).putInt("defaultsV", DEFAULTS_VERSION).apply()
    }

    fun addList(c: Context, url: String): Boolean {
        val l = lists(c)
        if (url in l) return false
        saveLists(c, l + url)
        return true
    }

    // ---- Listas activadas / desactivadas (las desactivadas se guardan pero no se cargan) ----
    fun disabledLists(c: Context): Set<String> =
        p(c).getStringSet("disabledLists", emptySet())?.toSet() ?: emptySet()

    fun isListEnabled(c: Context, url: String) = url !in disabledLists(c)

    fun setListEnabled(c: Context, url: String, on: Boolean) {
        val d = disabledLists(c).toMutableSet()
        if (on) d.remove(url) else d.add(url)
        p(c).edit().putStringSet("disabledLists", d).apply()
    }

    fun replaceList(c: Context, old: String, new: String): Boolean {
        val l = lists(c)
        if (new != old && new in l) return false
        val wasOff = !isListEnabled(c, old)
        saveLists(c, l.map { if (it == old) new else it })
        if (new != old) {
            cacheFile(c, old).delete()
            setListEnabled(c, old, true)
            if (wasOff) setListEnabled(c, new, false)
        }
        return true
    }

    fun removeList(c: Context, url: String) {
        saveLists(c, lists(c) - url)
        cacheFile(c, url).delete()
        setListEnabled(c, url, true)
    }

    // ---- Listas locales (archivos importados desde el equipo) ----
    const val LOCAL_PREFIX = "local://"

    fun isLocal(url: String) = url.startsWith(LOCAL_PREFIX)

    fun displayName(url: String) = if (isLocal(url)) url.removePrefix(LOCAL_PREFIX) else url

    /** Guarda el contenido de un archivo M3U importado. Si ya existía uno con ese nombre, lo reemplaza. @return true si es nueva. */
    fun addLocal(c: Context, name: String, text: String): Boolean {
        val clean = name.trim().ifEmpty { "lista" }.replace("/", "-")
        val url = LOCAL_PREFIX + clean
        runCatching { cacheFile(c, url).writeText(text) }
        val l = lists(c)
        if (url in l) return false
        saveLists(c, l + url)
        setListEnabled(c, url, true)
        return true
    }

    private fun cacheFile(c: Context, url: String) = File(c.filesDir, "lista_${url.hashCode()}.m3u")

    private fun fetch(url: String): String? = try {
        val req = Request.Builder().url(url).header("User-Agent", UrlUtils.USER_AGENT).build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return null
            val t = r.body?.string() ?: return null
            if (t.contains("#EXTINF", ignoreCase = true)) t else null
        }
    } catch (e: Exception) {
        null
    }

    /** La lista inicial numera sus canales por sección; las películas están en el rango 700-799. */
    private fun isMovie(title: String): Boolean {
        val n = Regex("^\\s*(\\d{3})\\b").find(title)?.groupValues?.get(1)?.toIntOrNull() ?: return false
        return n in 700..799
    }

    fun onlyMovies(c: Context): Boolean = p(c).getBoolean("onlyMovies", true)
    fun setOnlyMovies(c: Context, on: Boolean) = p(c).edit().putBoolean("onlyMovies", on).apply()

    private fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "")

    /**
     * Carga todas las listas. Con refresh=false usa lo guardado (y solo descarga las que no tengan copia).
     * Si una lista falla, se usa su copia guardada. Bloqueante: llamar fuera del hilo principal.
     * @return canales y cantidad de listas que no se pudieron actualizar.
     */
    fun load(c: Context, refresh: Boolean): Pair<List<Ch>, Int> {
        val map = LinkedHashMap<String, Ch>()
        var failed = 0
        val off = disabledLists(c)
        for ((idx, url) in lists(c).withIndex()) {
            if (url in off) continue
            val cache = cacheFile(c, url)
            val local = isLocal(url)
            val attempt = !local && (refresh || !cache.exists())
            val fetched = if (attempt) (fetch(url) ?: FALLBACK[url]?.let { fetch(it) }) else null
            val text: String? = when {
                fetched != null -> {
                    runCatching { cache.writeText(fetched) }
                    fetched
                }
                cache.exists() -> {
                    if (attempt) failed++
                    cache.readText()
                }
                else -> {
                    if (!local) failed++
                    null
                }
            }
            if (text == null) continue
            val parsed = try {
                M3uParser.parse(BufferedReader(StringReader(text)), if (local) null else url)
            } catch (e: Exception) {
                emptyList()
            }
            // Lista inicial: solo películas (si el filtro no encuentra nada, se muestra completa)
            val movies = if (url == DEFAULT_URL && onlyMovies(c)) parsed.filter { isMovie(it.title) } else parsed
            val items = if (movies.isNotEmpty()) movies else parsed
            for (it in items) {
                val key = norm(it.title)
                if (key.isEmpty()) continue
                if (it.url in map) continue
                map[it.url] = Ch(it.url, key, it.title, it.group, mutableListOf(it.url), idx + 1)
            }
        }
        val list = map.values.toList()
        val counts = list.groupingBy { it.key }.eachCount()
        list.forEach { it.dup = (counts[it.key] ?: 0) > 1 }
        return Pair(list, failed)
    }

    // ---- Favoritos (por nombre de canal, así valen para cualquier lista) ----
    /**
     * Favoritos que vienen puestos de fábrica, por versión (nombre normalizado, igual que [norm]).
     * Al subir la versión, las instalaciones existentes reciben solo los nuevos, una sola vez
     * (si los quitas con la estrella, no vuelven).
     */
    private val DEFAULT_FAVS: Map<Int, List<String>> = mapOf(
        1 to listOf("TV Globo Brasilia (1080p)"),
        2 to listOf(
            "Warner Channel Colombia (1080p)", "Xtrema Cartoons", "Gospel Cartoon (360p)",
            "Record News (720p)", "RecordTV Interior SP", "Retro Cartoon (720p)", "SBT Cuiaba",
            "HBO Family Latin America (1080p)", "HBO Latin America (1080p)", "Historia (1080p)",
            "Historia Y Vida (720p)", "History 2 Latin America (1080p)", "Maxcine (720p)",
            "Maxcine Comedy (720p)", "ESPN Premium HD (1080p)", "Fox Sports (1080p)", "Fox Sports HD",
            "France 24 Spanish (1080p)", "Garage TV Latin America", "Golden Plus (1080p)",
            "HBO 2 Latin America HD (1080p)", "Cine Sony (720p)", "CNN en Espanol (1080p)",
            "Crimen & Historia (720p)", "Discovery Channel Latin America South",
            "Disney Jr. Latin America (1080p)", "El Chavo TV (720p)", "ESPN 2 Latin America (1080p)",
            "731 Pluto TV Cine Suspenso", "733 FreeTV Terror", "742 FreeTV Estelar",
            "Adrenalina Pura TV (720p)", "Bob Esponja (720p)", "Bob Esponja Pantalones Cuadrados (720p)",
            "Cine Friki (1080p)", "Gospel Cartoon | BR", "PRIDEtv Latam | CL", "708 FreeTV Accion",
            "711 FreeTV Familia", "713 Runtime Familia", "716 FreeTV Clásico", "729 Pluto TV Cine Crimen"
        )
    )

    fun favs(c: Context): Set<String> {
        val s = p(c).getStringSet("favs", emptySet())!!.toMutableSet()
        val seen = p(c).getInt("defaultFavsV", 0)
        val latest = DEFAULT_FAVS.keys.max()
        if (seen < latest) {
            for ((v, names) in DEFAULT_FAVS) if (v > seen) s.addAll(names.map { norm(it) })
            p(c).edit().putStringSet("favs", s).putInt("defaultFavsV", latest).apply()
        }
        return s
    }

    fun toggleFav(c: Context, key: String): Set<String> {
        val s = favs(c).toMutableSet()
        if (!s.add(key)) s.remove(key)
        p(c).edit().putStringSet("favs", s).apply()
        return s
    }

    // ---- Copia de seguridad (URLs de listas + canales favoritos) ----
    /** Crea el JSON de respaldo. [all] sirve para guardar también nombre y URL legibles de cada favorito. */
    fun backupJson(c: Context, all: List<Ch>): String {
        val root = JSONObject()
        root.put("app", "PelisLD-TV")
        root.put("version", 1)
        root.put("fecha", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date()))
        val ls = JSONArray()
        val off = disabledLists(c)
        for (u in lists(c)) if (!isLocal(u)) ls.put(JSONObject().put("url", u).put("activa", u !in off))
        root.put("listas", ls)
        val byKey = HashMap<String, Ch>()
        for (ch in all) byKey.putIfAbsent(ch.key, ch)
        val fv = JSONArray()
        for (k in favs(c).sorted()) {
            val ch = byKey[k]
            fv.put(JSONObject().put("key", k).put("nombre", ch?.name ?: k).put("url", ch?.id ?: ""))
        }
        root.put("favoritos", fv)
        return root.toString(2)
    }

    /** Restaura un respaldo sumando lo que falte (no borra lo que ya hay). @return (listas nuevas, favoritos nuevos) o null si el archivo no es válido. */
    fun restoreJson(c: Context, text: String): Pair<Int, Int>? {
        val root = try { JSONObject(text) } catch (e: Exception) { return null }
        if (root.optString("app") != "PelisLD-TV") return null
        val current = lists(c).toMutableList()
        var newLists = 0
        val ls = root.optJSONArray("listas") ?: JSONArray()
        for (i in 0 until ls.length()) {
            val o = ls.optJSONObject(i) ?: continue
            val u = o.optString("url").trim()
            if (!u.startsWith("http", ignoreCase = true)) continue
            if (u !in current) { current.add(u); newLists++ }
            setListEnabled(c, u, o.optBoolean("activa", true))
        }
        saveLists(c, current)
        val fs = favs(c).toMutableSet()
        var newFavs = 0
        val fv = root.optJSONArray("favoritos") ?: JSONArray()
        for (i in 0 until fv.length()) {
            val k = fv.optJSONObject(i)?.optString("key").orEmpty()
            if (k.isNotEmpty() && fs.add(k)) newFavs++
        }
        p(c).edit().putStringSet("favs", fs).apply()
        return Pair(newLists, newFavs)
    }

    // ---- Canales caídos ----
    // Se marca "caído" tras 2 fallos seguidos; si reproduce bien, se borra la marca.
    // La marca vence a los 3 días: el canal vuelve a la lista y, si sigue fallando, se marca de nuevo.
    private const val DOWN_FAILS = 2
    private const val DOWN_TTL_MS = 3L * 24 * 60 * 60 * 1000

    private fun downMap(c: Context): JSONObject =
        try { JSONObject(p(c).getString("down", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }

    private fun saveDown(c: Context, o: JSONObject) = p(c).edit().putString("down", o.toString()).apply()

    /** Un intento de reproducción falló. @return true si el canal quedó marcado como caído. */
    fun markFail(c: Context, id: String): Boolean {
        val o = downMap(c)
        val fails = (o.optJSONObject(id)?.optInt("f", 0) ?: 0) + 1
        o.put(id, JSONObject().put("f", fails).put("t", System.currentTimeMillis()))
        saveDown(c, o)
        return fails >= DOWN_FAILS
    }

    /** Ninguna fuente funcionó: el canal queda marcado como caído de inmediato. */
    fun markDown(c: Context, id: String) {
        val o = downMap(c)
        o.put(id, JSONObject().put("f", DOWN_FAILS).put("t", System.currentTimeMillis()))
        saveDown(c, o)
    }

    /** El canal reprodujo bien: se olvida cualquier fallo anterior. */
    fun markOk(c: Context, id: String) {
        val o = downMap(c)
        if (o.has(id)) { o.remove(id); saveDown(c, o) }
    }

    /** IDs de canales caídos vigentes (con 2+ fallos y marca no vencida). */
    fun downIds(c: Context): Set<String> {
        val o = downMap(c)
        val now = System.currentTimeMillis()
        val out = HashSet<String>()
        for (k in o.keys()) {
            val e = o.optJSONObject(k) ?: continue
            if (e.optInt("f", 0) >= DOWN_FAILS && now - e.optLong("t", 0) < DOWN_TTL_MS) out.add(k)
        }
        return out
    }

    fun clearDown(c: Context) = p(c).edit().remove("down").apply()
    fun hideDown(c: Context): Boolean = p(c).getBoolean("hideDown", false)
    fun setHideDown(c: Context, on: Boolean) = p(c).edit().putBoolean("hideDown", on).apply()

    // ---- Ajustes ----
    /** Ecualizador: encendido y nivel de cada banda (en milibelios). */
    fun eqOn(c: Context): Boolean = p(c).getBoolean("eqOn", true)
    fun setEqOn(c: Context, on: Boolean) = p(c).edit().putBoolean("eqOn", on).apply()
    fun eqLevels(c: Context): List<Int>? =
        p(c).getString("eqLevels", null)?.split(",")?.mapNotNull { it.toIntOrNull() }?.takeIf { it.isNotEmpty() }
    fun setEqLevels(c: Context, l: List<Int>) = p(c).edit().putString("eqLevels", l.joinToString(",")).apply()

    /** Calidad máxima elegida (alto en píxeles); 0 = automática. */
    fun maxHeight(c: Context): Int = p(c).getInt("maxH", 0)
    fun setMaxHeight(c: Context, h: Int) = p(c).edit().putInt("maxH", h).apply()
    fun last(c: Context): String? = p(c).getString("last", null)
    fun setLast(c: Context, key: String) = p(c).edit().putString("last", key).apply()
    fun dedupe(c: Context): Boolean = p(c).getBoolean("dedupe", false)
    fun setDedupe(c: Context, on: Boolean) = p(c).edit().putBoolean("dedupe", on).apply()
    fun pointer(c: Context): Boolean = p(c).getBoolean("pointer", false)
    fun setPointer(c: Context, on: Boolean) = p(c).edit().putBoolean("pointer", on).apply()
    /** Descanso visual: [filtro %, brillo %, tono 0-100]. */
    fun restCfg(c: Context): IntArray =
        p(c).let { intArrayOf(it.getInt("rA", 30), it.getInt("rB", 30), it.getInt("rT", 50)) }
    fun setRestCfg(c: Context, a: Int, b: Int, t: Int) =
        p(c).edit().putInt("rA", a).putInt("rB", b).putInt("rT", t).apply()
    fun rest(c: Context): Boolean = p(c).getBoolean("rest", false)
    fun setRest(c: Context, on: Boolean) = p(c).edit().putBoolean("rest", on).apply()
}
