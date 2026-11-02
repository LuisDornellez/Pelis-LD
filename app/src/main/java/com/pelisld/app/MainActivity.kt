package com.pelisld.app

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.graphics.Color
import android.content.res.Configuration
import android.media.audiofx.Equalizer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.os.SystemClock
import android.widget.HorizontalScrollView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory
import com.google.android.exoplayer2.trackselection.TrackSelectionOverride
import com.google.android.exoplayer2.upstream.DefaultDataSource
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource
import com.google.android.exoplayer2.util.MimeTypes
import com.pelisld.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var mainAd: ChAdapter
    private lateinit var overAd: ChAdapter
    private var player: ExoPlayer? = null
    private var eq: Equalizer? = null
    private val h = Handler(Looper.getMainLooper())

    private var all: List<Ch> = emptyList()
    private var shown: List<Ch> = emptyList()
    private var current: Ch? = null
    private val tried = mutableSetOf<String>()
    private var favs: Set<String> = emptySet()
    private var onlyFavs = false
    private var query = ""
    private var fullscreen = false
    private var rest = false
    private var dedupe = false
    private var pointerMode = false
    private var curX = 0f
    private var curY = 0f
    private var ptrDown = false
    private var ptrDownTime = 0L
    private val hideCursor = Runnable { b.pointer.alpha = 0f }
    private var hideDown = false
    private var down: Set<String> = emptySet()
    private var playing: Ch? = null   // fuente que realmente se está reproduciendo (puede ser alternativa)
    private var restA = 30
    private var restB = 30
    private var restT = 50
    private val gold = Color.parseColor("#D4AF37")
    private val ink = Color.parseColor("#0B0B10")

    // ---- Copia de seguridad: guardar / restaurar archivo JSON ----
    private val backupSave = registerForActivityResult(ActivityResultContracts.CreateDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        val json = Store.backupJson(this, all)
        try {
            contentResolver.openOutputStream(uri, "wt")?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            toast("Copia guardada: ${Store.lists(this).size} lista(s) y ${Store.favs(this).size} favorito(s)")
        } catch (e: Exception) {
            toast("No se pudo guardar la copia")
        }
    }

    private val backupLoad = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        val text = try {
            contentResolver.openInputStream(uri)?.use { String(it.readBytes(), Charsets.UTF_8) }
        } catch (e: Exception) { null }
        val r = text?.let { Store.restoreJson(this, it) }
        if (r == null) {
            toast("Ese archivo no es una copia válida de Pelis LD")
        } else {
            favs = Store.favs(this)
            toast("Restaurado: ${r.first} lista(s) y ${r.second} favorito(s) nuevos")
            reload(refresh = true, notify = false)
        }
    }

    // ---- Importar archivos M3U desde el equipo (se pueden elegir varios a la vez) ----
    private val importFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        val app = applicationContext
        Thread {
            var ok = 0
            var bad = 0
            for (uri in uris) {
                try {
                    val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    val text = bytes?.let { String(it, Charsets.UTF_8) }
                    if (text == null || !text.contains("#EXTINF", ignoreCase = true)) { bad++; continue }
                    var name = "lista"
                    contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) name = c.getString(0) ?: name
                    }
                    Store.addLocal(app, name, text)
                    ok++
                } catch (e: Exception) { bad++ }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (ok > 0) {
                    toast("Importado: $ok archivo(s)" + if (bad > 0) " · $bad no válido(s)" else "")
                    reload(refresh = false, notify = true)
                } else {
                    toast("Ese archivo no es una lista M3U válida")
                }
            }
        }.start()
    }

    private val hideControls = Runnable {
        b.controls.visibility = View.GONE
        b.tvTitle.visibility = View.GONE
        b.tvTitleText.visibility = View.GONE
    }
    private val hideOverlay = Runnable { hideOverlayNow() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        favs = Store.favs(this)
        onlyFavs = favs.isNotEmpty()   // al abrir la app se muestran primero los favoritos
        rest = Store.rest(this)
        dedupe = Store.dedupe(this)
        pointerMode = Store.pointer(this)
        hideDown = Store.hideDown(this)
        Store.restCfg(this).let { restA = it[0]; restB = it[1]; restT = it[2] }

        mainAd = ChAdapter({ pick(it) }, { onLongChannel(it) })
        overAd = ChAdapter({ pick(it); hideOverlayNow() }, { onLongChannel(it) }, big = true)
        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = mainAd
        b.overlayList.layoutManager = LinearLayoutManager(this)
        b.overlayList.adapter = overAd
        b.overlayList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (b.overlayList.visibility == View.VISIBLE) scheduleHideOverlay()
            }
        })

        b.overlayList.viewTreeObserver.addOnGlobalFocusChangeListener { _, _ ->
            if (b.overlayList.visibility == View.VISIBLE) scheduleHideOverlay()
        }
        b.playerView.isFocusable = true
        b.playerView.isFocusableInTouchMode = false

        val prev = View.OnClickListener { step(-1) }
        val next = View.OnClickListener { step(1) }
        val fav = View.OnClickListener { current?.let { toggleFav(it.key) } }
        val restClick = View.OnClickListener { setRest(!rest) }
        b.btnPrev.setOnClickListener(prev); b.cPrev.setOnClickListener(prev)
        b.btnNext.setOnClickListener(next); b.cNext.setOnClickListener(next)
        b.btnFav.setOnClickListener(fav); b.cFav.setOnClickListener(fav)
        b.btnRest.setOnClickListener(restClick); b.cRest.setOnClickListener(restClick)
        b.btnRestTune.setOnClickListener { showRestDialog() }
        b.btnRest.setOnLongClickListener { showRestDialog(); true }
        b.cRest.setOnLongClickListener { showRestDialog(); true }
        b.cFull.setOnClickListener { setFullscreen(!fullscreen) }
        b.btnFull.setOnClickListener { setFullscreen(true) }
        b.btnPtr.setOnClickListener { setPointerMode(!pointerMode, announce = true) }
        b.btnLists.setOnClickListener { showListsDialog() }
        b.btnQual.setOnClickListener { showQualityDialog() }
        b.cQual.setOnClickListener { showQualityDialog() }
        b.btnEq.setOnClickListener { showEqDialog() }
        b.cEq.setOnClickListener { showEqDialog() }
        b.btnDup.setOnClickListener {
            dedupe = !dedupe
            Store.setDedupe(this, dedupe)
            refreshShown()
        }
        b.btnDown.setOnClickListener {
            hideDown = !hideDown
            Store.setHideDown(this, hideDown)
            refreshShown()
            if (hideDown) {
                val n = all.count { it.id in down }
                toast(if (n == 0) "Aún no hay canales caídos. Se marcan solos cuando uno falla." else "Ocultando $n canales caídos")
            }
        }
        b.btnDown.setOnLongClickListener { confirmClearDown(); true }
        b.btnAll.setOnClickListener { onlyFavs = false; refreshShown() }
        b.btnFavs.setOnClickListener { onlyFavs = true; refreshShown() }
        b.search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { query = s?.toString()?.trim().orEmpty(); refreshShown() }
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, bf: Int, c: Int) {}
        })
        val playerClick = View.OnClickListener {
            if (fullscreen) {
                if (b.overlayList.visibility == View.VISIBLE) hideOverlayNow() else showOverlay()
                showControls()
            } else if (b.controls.visibility == View.VISIBLE) {
                h.removeCallbacks(hideControls); hideControls.run()
            } else showControls()
        }
        b.playerView.setOnClickListener(playerClick)
        b.playerBox.setOnClickListener(playerClick)
        b.playerBox.isFocusable = false

        b.credit.paintFlags = b.credit.paintFlags or Paint.UNDERLINE_TEXT_FLAG
        b.credit.setOnClickListener { openSite() }
        b.pointer.post {
            curX = b.pointer.width / 2f; curY = b.pointer.height / 2f
            b.pointer.setPos(curX, curY)
            applyPointer()
        }
        applyLayout()
        applyRest()
        refreshShown()
        reload(refresh = false, notify = false, chain = true)
    }

    // ---------- Hora ----------
    private val clockTick = object : Runnable {
        override fun run() {
            val t = android.text.format.DateFormat.getTimeFormat(this@MainActivity).format(java.util.Date())
            val d = android.text.format.DateFormat.getMediumDateFormat(this@MainActivity).format(java.util.Date())
            val sp = android.text.SpannableString("Hora: $t\n$d")
            val i = "Hora: $t\n".length
            sp.setSpan(android.text.style.RelativeSizeSpan(0.75f), i, sp.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            sp.setSpan(android.text.style.ForegroundColorSpan(0xFF8A8A99.toInt()), i, sp.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            b.tvClock.text = sp
            h.postDelayed(this, 60000L - System.currentTimeMillis() % 60000L + 50L)
        }
    }

    override fun onStart() {
        super.onStart()
        h.removeCallbacks(clockTick)
        clockTick.run()
        if (player == null) {
            createPlayer()
            current?.let { startStream(it) }
        }
    }

    override fun onStop() {
        super.onStop()
        h.removeCallbacks(clockTick)
        releaseEq()
        player?.release()
        player = null
        b.playerView.player = null
    }

    // ---------- Reproductor ----------
    private fun createPlayer() {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(UrlUtils.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(20000)
        val ds = DefaultDataSource.Factory(this, http)
        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(ds))
            .build()
        exo.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) { attachEq(audioSessionId) }

            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> status("Cargando…")
                    Player.STATE_READY -> {
                        status(null)
                        // Reprodujo bien: se borra cualquier fallo anterior de esta fuente
                        playing?.let { Store.markOk(this@MainActivity, it.id) }
                        refreshDown()
                    }
                    else -> {}
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    player?.seekToDefaultPosition(); player?.prepare(); return
                }
                val ch = current
                // Esta fuente falló: se cuenta el fallo (con 2 seguidos queda como caída)
                playing?.let { Store.markFail(this@MainActivity, it.id) }
                refreshDown()
                // Si el mismo canal existe en otra lista, se prueba esa fuente (mejor una que no esté caída)
                val cands = ch?.let { c -> all.filter { it.key == c.key && it.id !in tried } }.orEmpty()
                val other = cands.firstOrNull { it.id !in down } ?: cands.firstOrNull()
                if (other != null) {
                    tried.add(other.id)
                    status("Probando la fuente de la lista ${other.src}…")
                    startStream(other)
                } else {
                    // Ya no quedan fuentes por probar: todas las intentadas se marcan como caídas
                    for (id in tried) Store.markDown(this@MainActivity, id)
                    playing?.let { Store.markDown(this@MainActivity, it.id) }
                    refreshDown()
                    status("⚠ Canal caído.\nPrueba otro o agrega otra lista en Listas.")
                }
            }
        })
        // Calidad máxima elegida antes (0 = automática)
        Store.maxHeight(this).takeIf { it > 0 }?.let { h ->
            exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                .setMaxVideoSize(Int.MAX_VALUE, h).build()
        }
        b.playerView.player = exo
        player = exo
        attachEq(exo.audioSessionId)
    }

    // ---------- Ecualizador ----------
    private fun releaseEq() {
        runCatching { eq?.release() }
        eq = null
    }

    private fun attachEq(sessionId: Int) {
        releaseEq()
        if (sessionId == C.AUDIO_SESSION_ID_UNSET) return
        eq = try {
            Equalizer(0, sessionId).also { e ->
                val range = e.bandLevelRange
                Store.eqLevels(this)?.takeIf { it.size == e.numberOfBands.toInt() }?.forEachIndexed { i, l ->
                    e.setBandLevel(i.toShort(), l.coerceIn(range[0].toInt(), range[1].toInt()).toShort())
                }
                e.enabled = Store.eqOn(this)
            }
        } catch (ex: Exception) { null }
    }

    private fun showEqDialog() {
        val e = eq
        if (e == null) { toast("Reproduce un canal primero para usar el ecualizador"); return }
        val d = resources.displayMetrics.density
        val nBands = e.numberOfBands.toInt()
        val range = e.bandLevelRange
        val minL = range[0].toInt()
        val maxL = range[1].toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * d).toInt(), (12 * d).toInt(), (20 * d).toInt(), 0)
        }
        val sw = SwitchCompat(this).apply {
            text = "Ecualizador activado"
            isChecked = e.enabled
            setOnCheckedChangeListener { _, on ->
                runCatching { e.enabled = on }
                Store.setEqOn(this@MainActivity, on)
            }
        }
        box.addView(sw)

        val bars = ArrayList<SeekBar>()
        val labels = ArrayList<TextView>()
        fun freq(band: Int): String {
            val hz = e.getCenterFreq(band.toShort()) / 1000
            return if (hz >= 1000) String.format(java.util.Locale.US, "%.1f kHz", hz / 1000.0).replace(".0 ", " ") else "$hz Hz"
        }
        fun db(mb: Int) = String.format(java.util.Locale.US, "%+.1f dB", mb / 100.0)
        fun refresh() {
            for (i in 0 until nBands) {
                val lv = e.getBandLevel(i.toShort()).toInt()
                bars[i].progress = lv - minL
                labels[i].text = "${freq(i)}: ${db(lv)}"
            }
        }

        val presetBtn = TextView(this).apply {
            text = "Preajustes…"
            setTextColor(Color.WHITE)
            textSize = 13f
            setBackgroundResource(R.drawable.bg_chip)
            gravity = android.view.Gravity.CENTER
            setPadding((16 * d).toInt(), (10 * d).toInt(), (16 * d).toInt(), (10 * d).toInt())
            isFocusable = true; isClickable = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = (10 * d).toInt() }
        }
        box.addView(presetBtn)

        for (i in 0 until nBands) {
            val tv = TextView(this).apply { setPadding(0, (12 * d).toInt(), 0, 0) }
            val sb = SeekBar(this).apply { max = maxL - minL }
            sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    tv.text = "${freq(i)}: ${db(p + minL)}"
                    if (!fromUser) return
                    runCatching { e.setBandLevel(i.toShort(), (p + minL).toShort()) }
                    if (!sw.isChecked) sw.isChecked = true
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
            labels.add(tv); bars.add(sb)
            box.addView(tv); box.addView(sb)
        }
        refresh()

        presetBtn.setOnClickListener {
            val n = e.numberOfPresets.toInt()
            if (n == 0) { toast("Este dispositivo no trae preajustes"); return@setOnClickListener }
            val names = Array(n) { e.getPresetName(it.toShort()) }
            AlertDialog.Builder(this)
                .setTitle("Preajustes")
                .setItems(names) { _, which ->
                    runCatching { e.usePreset(which.toShort()) }
                    if (!sw.isChecked) sw.isChecked = true
                    refresh()
                }
                .show()
        }

        val dlg = AlertDialog.Builder(this)
            .setTitle("Ecualizador")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Listo", null)
            .setNeutralButton("Restablecer", null)
            .create()
        dlg.setOnDismissListener {
            runCatching { Store.setEqLevels(this, (0 until nBands).map { e.getBandLevel(it.toShort()).toInt() }) }
        }
        dlg.show()
        dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            for (i in 0 until nBands) runCatching { e.setBandLevel(i.toShort(), 0) }
            refresh()
        }
    }

    /** Calidades de video disponibles en el canal actual (alto en píxeles, de mayor a menor). */
    private fun videoHeights(exo: ExoPlayer): List<Int> {
        val hs = HashSet<Int>()
        for (g in exo.currentTracks.groups) {
            if (g.type != C.TRACK_TYPE_VIDEO) continue
            for (i in 0 until g.length) if (g.isTrackSupported(i)) g.getTrackFormat(i).height.takeIf { it > 0 }?.let { hs.add(it) }
        }
        return hs.sortedDescending()
    }

    private fun showQualityDialog() {
        val exo = player
        if (exo == null || current == null) { toast("Primero elige un canal"); return }
        val heights = videoHeights(exo)
        if (heights.size < 2) { toast("Este canal solo ofrece una calidad"); return }
        val saved = Store.maxHeight(this)
        val nowH = exo.videoFormat?.height ?: 0
        val labels = ArrayList<String>()
        labels.add("Automática" + if (saved == 0) "  ✓" else "")
        for (h in heights) {
            labels.add("${h}p" + (if (h == saved) "  ✓" else "") + (if (h == nowH) "  (reproduciendo)" else ""))
        }
        AlertDialog.Builder(this)
            .setTitle("Calidad de video")
            .setItems(labels.toTypedArray()) { _, which -> applyQuality(if (which == 0) 0 else heights[which - 1]) }
            .show()
    }

    private fun applyQuality(h: Int) {
        val exo = player ?: return
        Store.setMaxHeight(this, h)
        val pb = exo.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO)
        if (h <= 0) {
            pb.clearVideoSizeConstraints()
        } else {
            // Para canales siguientes: tope de calidad. Para este: se fija la pista elegida.
            pb.setMaxVideoSize(Int.MAX_VALUE, h)
            var bestG: com.google.android.exoplayer2.Tracks.Group? = null
            var bestI = -1
            var bestBr = -1
            for (g in exo.currentTracks.groups) {
                if (g.type != C.TRACK_TYPE_VIDEO) continue
                for (i in 0 until g.length) {
                    val f = g.getTrackFormat(i)
                    if (g.isTrackSupported(i) && f.height == h && f.bitrate > bestBr) { bestG = g; bestI = i; bestBr = f.bitrate }
                }
            }
            bestG?.let { pb.setOverrideForType(TrackSelectionOverride(it.mediaTrackGroup, bestI)) }
        }
        exo.trackSelectionParameters = pb.build()
        toast(if (h <= 0) "Calidad automática" else "Calidad: ${h}p")
    }

    private fun startStream(ch: Ch) {
        playing = ch
        val url = ch.urls.firstOrNull() ?: return
        val mi = MediaItem.Builder().setUri(url).apply {
            if (UrlUtils.isHls(url)) setMimeType(MimeTypes.APPLICATION_M3U8)
        }.build()
        player?.apply { setMediaItem(mi); prepare(); playWhenReady = true }
    }

    private fun pick(ch: Ch) {
        current = ch
        tried.clear()
        tried.add(ch.id)
        Store.setLast(this, ch.id)
        b.tvTitleText.text = ch.name
        status("Cargando…")
        startStream(ch)
        if (hideDown) refreshShown() else updateUi()
        val i = shown.indexOfFirst { it.id == ch.id }
        if (i >= 0) b.list.scrollToPosition(i)
        showControls()
    }

    private fun step(d: Int) {
        val l = if (shown.isNotEmpty()) shown else all
        if (l.isEmpty()) return
        val i = l.indexOfFirst { it.id == current?.id }
        val n = if (i < 0) 0 else (i + d + l.size) % l.size
        pick(l[n])
    }

    private fun status(msg: String?) {
        b.status.text = msg ?: ""
        b.status.visibility = if (msg == null) View.GONE else View.VISIBLE
    }

    // ---------- Lista y favoritos ----------
    private fun refreshShown() {
        val q = query.lowercase()
        // Sin duplicados: queda el primer canal de cada nombre (según el orden de las listas)
        down = Store.downIds(this)
        val visible = if (hideDown) all.filter { it.id !in down || it.id == current?.id } else all
        val hidden = all.size - visible.size
        val base = if (dedupe) visible.distinctBy { it.key }.map { it.copy() } else visible
        chip(b.btnDup, dedupe)
        chip(b.btnDown, hideDown)
        shown = base.filter { (!onlyFavs || it.key in favs) && (q.isEmpty() || it.name.lowercase().contains(q)) }
        chip(b.btnAll, !onlyFavs)
        chip(b.btnFavs, onlyFavs)
        b.tvCount.text = "${base.size} canales" + if (hideDown && hidden > 0) " · $hidden caídos ocultos" else ""
        updateUi()
    }

    private fun chip(v: TextView, on: Boolean) {
        v.isSelected = on
        v.setTextColor(if (on) ink else Color.WHITE)
        v.setCompoundDrawableTintList(ColorStateList.valueOf(if (on) ink else Color.WHITE))
    }

    private fun updateUi() {
        val id = current?.id
        mainAd.submit(shown, id, favs, down)
        overAd.submit(shown, id, favs, down)
        val k = current?.key
        val isFav = k != null && k in favs
        val res = if (isFav) R.drawable.ic_star else R.drawable.ic_star_border
        val tint = if (isFav) gold else Color.WHITE
        b.btnFav.setImageResource(res); b.btnFav.setColorFilter(tint)
        b.cFav.setImageResource(res); b.cFav.setColorFilter(tint)
    }

    /** Vuelve a calcular qué canales están caídos y refresca la lista. */
    private fun refreshDown() {
        val now = Store.downIds(this)
        if (now != down) refreshShown()
    }

    /** Pulsación larga: en un canal caído ofrece restaurarlo; en los demás, favorito como siempre. */
    private fun onLongChannel(ch: Ch) {
        if (ch.id !in down) { toggleFav(ch.key); return }
        val fav = if (ch.key in favs) "Quitar de favoritos" else "Agregar a favoritos"
        AlertDialog.Builder(this)
            .setTitle(ch.name)
            .setItems(arrayOf("Restaurar canal (ya no está caído)", fav)) { _, which ->
                if (which == 0) {
                    Store.markOk(this, ch.id)
                    refreshShown()
                    toast("Canal restaurado")
                } else toggleFav(ch.key)
            }
            .show()
    }

    private fun confirmClearDown() {
        val n = down.size
        AlertDialog.Builder(this)
            .setTitle("Canales caídos")
            .setMessage(if (n == 0) "No hay canales marcados como caídos." else "Hay $n canal(es) marcados como caídos.\n¿Limpiar la lista para volver a probarlos?")
            .setPositiveButton(if (n == 0) "Cerrar" else "Limpiar") { _, _ ->
                if (n > 0) { Store.clearDown(this); refreshShown(); toast("Lista de caídos limpiada") }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun toggleFav(key: String) {
        favs = Store.toggleFav(this, key)
        refreshShown()
    }

    private fun reload(refresh: Boolean, notify: Boolean, chain: Boolean = false) {
        val app = applicationContext
        Thread {
            val (chs, failed) = Store.load(app, refresh)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                all = chs
                if (onlyFavs && chs.none { it.key in favs }) onlyFavs = false
                refreshShown()
                if (current == null && chs.isNotEmpty()) {
                    val last = Store.last(this)
                    val lastCh = chs.firstOrNull { it.id == last }
                    // Arranca en un favorito: el último visto si es favorito, si no el primero de favoritos
                    val start = (lastCh?.takeIf { it.key in favs } ?: chs.firstOrNull { it.key in favs })
                        ?: lastCh ?: chs[0]
                    pick(start)
                } else if (chs.isEmpty()) {
                    status("Sin canales.\nAbre Listas y agrega una URL.")
                }
                if (notify || (refresh && failed > 0)) {
                    val extra = if (failed > 0) " · $failed lista(s) sin conexión, uso lo guardado" else ""
                    toast("${chs.size} canales de ${Store.lists(this).count { Store.isListEnabled(this, it) }} lista(s)$extra")
                }
                if (chain) reload(refresh = true, notify = false)
            }
        }.start()
    }

    private fun openSite() {
        val url = "https://www.luisdornellez.com"
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            (getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                ?.setPrimaryClip(ClipData.newPlainText("web", url))
            toast("No hay navegador en este equipo. Copié el enlace: $url")
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    // ---------- Diálogo de listas ----------
    private fun showListsDialog() {
        val d = resources.displayMetrics.density
        fun px(v: Int) = (v * d).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(8), px(20), 0)
        }
        var dlg: AlertDialog? = null
        val input = EditText(this).apply {
            hint = "https://…/lista.m3u"
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        box.addView(input)
        box.addView(android.widget.Button(this).apply {
            text = "Importar archivo M3U"
            isAllCaps = false
            setOnClickListener {
                dlg?.dismiss()
                try { importFiles.launch(arrayOf("*/*")) } catch (e: Exception) { toast("No se pudo abrir el selector de archivos") }
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        box.addView(CheckBox(this).apply {
            text = "Lista inicial: solo películas"
            isChecked = Store.onlyMovies(this@MainActivity)
            setOnCheckedChangeListener { _, on ->
                Store.setOnlyMovies(this@MainActivity, on)
                reload(refresh = false, notify = true)
            }
        })
        box.addView(TextView(this).apply {
            text = "COPIA DE SEGURIDAD"
            textSize = 11f
            letterSpacing = 0.12f
            setTextColor(gold)
            setPadding(0, px(18), 0, px(8))
        })
        val bkRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bkRow.addView(android.widget.Button(this).apply {
            text = "Guardar copia"
            isAllCaps = false
            setOnClickListener {
                val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date())
                try { backupSave.launch("PelisLD-copia-$stamp.json") } catch (e: Exception) { toast("No se pudo abrir el selector de archivos") }
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { it.marginEnd = px(6) })
        bkRow.addView(android.widget.Button(this).apply {
            text = "Restaurar copia"
            isAllCaps = false
            setOnClickListener {
                dlg?.dismiss()
                try { backupLoad.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*")) } catch (e: Exception) { toast("No se pudo abrir el selector de archivos") }
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(bkRow)
        box.addView(TextView(this).apply {
            text = "LISTAS GUARDADAS"
            textSize = 11f
            letterSpacing = 0.12f
            setTextColor(gold)
            setPadding(0, px(18), 0, px(8))
        })
        fun iconBtn(res: Int, desc: String, tint: Int, onClick: () -> Unit) = ImageView(this).apply {
            setImageResource(res)
            setColorFilter(tint)
            contentDescription = desc
            setPadding(px(10), px(10), px(10), px(10))
            setBackgroundResource(R.drawable.bg_icon_flat)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
        fun bp() = LinearLayout.LayoutParams(px(40), px(40))
        Store.lists(this).forEach { url ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.bg_card)
                setPadding(px(14), px(6), px(6), px(6))
            }
            val local = Store.isLocal(url)
            val urlView = TextView(this).apply {
                text = if (local) "📁 " + Store.displayName(url) else url
                textSize = 12f
                setTextColor(Color.parseColor("#C9C9D4"))
                setTextIsSelectable(true)
                alpha = if (Store.isListEnabled(this@MainActivity, url)) 1f else 0.4f
            }
            row.addView(android.widget.Switch(this).apply {
                isChecked = Store.isListEnabled(this@MainActivity, url)
                contentDescription = "Activar o desactivar lista"
                setPadding(0, 0, px(8), 0)
                setOnCheckedChangeListener { _, on ->
                    Store.setListEnabled(this@MainActivity, url, on)
                    urlView.alpha = if (on) 1f else 0.4f
                    reload(refresh = false, notify = true)
                }
            })
            row.addView(urlView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (!local) row.addView(iconBtn(R.drawable.ic_copy, "Copiar", gold) {
                (getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                    ?.setPrimaryClip(ClipData.newPlainText("lista", url))
                toast("Link copiado")
            }, bp())
            if (!local) row.addView(iconBtn(R.drawable.ic_edit, "Editar", Color.WHITE) {
                val ed = EditText(this@MainActivity).apply {
                    setText(url)
                    setSelection(url.length)
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or
                        InputType.TYPE_TEXT_FLAG_MULTI_LINE
                }
                val holder = LinearLayout(this@MainActivity).apply {
                    setPadding(px(20), px(8), px(20), 0)
                    addView(ed, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                }
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Editar lista")
                    .setView(holder)
                    .setPositiveButton("Guardar") { _, _ ->
                        var u = ed.text.toString().trim()
                        if (u.isEmpty()) return@setPositiveButton
                        if (!u.contains("://")) u = "https://$u"
                        if (!u.startsWith("http", ignoreCase = true)) {
                            toast("La URL no es válida")
                        } else if (!Store.replaceList(this@MainActivity, url, u)) {
                            toast("Esa lista ya estaba guardada")
                        } else {
                            dlg?.dismiss()
                            toast("Lista actualizada")
                            reload(refresh = true, notify = true)
                        }
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }, bp())
            row.addView(iconBtn(R.drawable.ic_delete, "Quitar", Color.parseColor("#E5645B")) {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage("¿Quitar esta lista?\n$url")
                    .setPositiveButton("Quitar") { _, _ ->
                        Store.removeList(this@MainActivity, url)
                        dlg?.dismiss()
                        reload(refresh = false, notify = true)
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }, bp())
            box.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = px(8) })
        }
        dlg = AlertDialog.Builder(this)
            .setTitle("Listas de canales")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Agregar") { _, _ ->
                var u = input.text.toString().trim()
                if (u.isEmpty()) return@setPositiveButton
                if (!u.contains("://")) u = "https://$u"
                if (!u.startsWith("http", ignoreCase = true)) {
                    toast("La URL no es válida")
                } else if (!Store.addList(this, u)) {
                    toast("Esa lista ya estaba guardada")
                } else {
                    toast("Descargando lista…")
                    reload(refresh = true, notify = true)
                }
            }
            .setNeutralButton("Actualizar") { _, _ -> reload(refresh = true, notify = true) }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    // ---------- Pantalla completa, controles y lista flotante ----------
    private fun setFullscreen(on: Boolean) {
        fullscreen = on
        requestedOrientation = if (on) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        val c = WindowInsetsControllerCompat(window, window.decorView)
        if (on) {
            c.hide(WindowInsetsCompat.Type.systemBars())
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            c.show(WindowInsetsCompat.Type.systemBars())
        }
        b.cFull.setImageResource(if (on) R.drawable.ic_full_exit else R.drawable.ic_full)
        hideOverlayNow()
        applyLayout()
        showControls()
        if (on) b.playerView.requestFocus() else b.btnFull.post { b.btnFull.requestFocus() }
        if (pointerMode) wakeCursor()
    }

    private fun applyLayout() {
        val land = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val pb = b.playerBox.layoutParams as LinearLayout.LayoutParams
        val pn = b.panel.layoutParams as LinearLayout.LayoutParams
        val mp = ViewGroup.LayoutParams.MATCH_PARENT
        if (fullscreen) {
            b.panel.visibility = View.GONE
            b.content.orientation = LinearLayout.VERTICAL
            pb.width = mp; pb.height = mp; pb.weight = 0f
        } else {
            b.panel.visibility = View.VISIBLE
            if (land) {
                b.content.orientation = LinearLayout.HORIZONTAL
                pb.width = 0; pb.height = mp; pb.weight = 3f
                pn.width = 0; pn.height = mp; pn.weight = 2f
            } else {
                b.content.orientation = LinearLayout.VERTICAL
                pb.width = mp; pb.height = resources.displayMetrics.widthPixels * 9 / 16; pb.weight = 0f
                pn.width = mp; pn.height = 0; pn.weight = 1f
            }
        }
        b.playerBox.layoutParams = pb
        b.panel.layoutParams = pn
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyLayout()
    }

    private fun showControls() {
        b.controls.visibility = View.VISIBLE
        b.tvTitle.visibility = View.VISIBLE
        b.tvTitleText.visibility = View.VISIBLE
        h.removeCallbacks(hideControls)
        h.postDelayed(hideControls, 4500)
    }

    /** Mientras la lista flotante está abierta, los botones y el título se corren para quedar libres. */
    private fun shiftForOverlay(on: Boolean) {
        val m = if (on) (360 * resources.displayMetrics.density).toInt() else 0
        // El título ocupa todo el ancho, así que se le resta el margen completo.
        val t = b.tvTitleText.layoutParams as FrameLayout.LayoutParams
        if (t.marginEnd != m) { t.marginEnd = m; b.tvTitleText.layoutParams = t }
        // Los botones están centrados: en un FrameLayout el margen derecho empuja el centro
        // el margen completo, por eso se usa la mitad para centrarlos en el espacio libre.
        val c = b.controls.layoutParams as FrameLayout.LayoutParams
        val half = m / 2
        if (c.marginEnd != half || c.rightMargin != half) {
            c.marginEnd = half; c.rightMargin = half
            b.controls.layoutParams = c
        }
    }

    private fun showOverlay() {
        shiftForOverlay(true)
        b.overlayList.visibility = View.VISIBLE
        val i = shown.indexOfFirst { it.id == current?.id }.coerceAtLeast(0)
        b.overlayList.scrollToPosition(i)
        b.overlayList.post { b.overlayList.findViewHolderForAdapterPosition(i)?.itemView?.requestFocus() }
        scheduleHideOverlay()
    }

    private fun scheduleHideOverlay() {
        h.removeCallbacks(hideOverlay)
        h.postDelayed(hideOverlay, 7000)
    }

    private fun hideOverlayNow() {
        h.removeCallbacks(hideOverlay)
        b.overlayList.visibility = View.GONE
        shiftForOverlay(false)
        if (fullscreen) b.playerView.requestFocus()
    }

    // ---------- Descanso visual ----------
    private fun setRest(on: Boolean) {
        rest = on
        Store.setRest(this, on)
        applyRest()
    }

    private fun applyRest() {
        b.rest.visibility = if (rest) View.VISIBLE else View.GONE
        val lp = window.attributes
        lp.screenBrightness = if (rest) (restB / 100f).coerceAtLeast(0.05f) else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        val hue = 55f - restT * 0.45f
        b.rest.setBackgroundColor(Color.HSVToColor(restA * 153 / 100, floatArrayOf(hue, 1f, 1f)))
        window.attributes = lp
        chip(b.btnRest, rest)
        b.cRest.setColorFilter(if (rest) gold else Color.WHITE)
    }

    private fun showRestDialog() {
        if (!rest) setRest(true)
        val d = resources.displayMetrics.density
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * d).toInt(), (12 * d).toInt(), (20 * d).toInt(), 0)
        }
        val resets = mutableListOf<() -> Unit>()
        fun add(label: String, min: Int, max: Int, start: Int, def: Int, unit: String, set: (Int) -> Unit) {
            val tv = TextView(this).apply { setPadding(0, (12 * d).toInt(), 0, 0) }
            val sb = SeekBar(this).apply { this.max = max - min; progress = start - min }
            tv.text = "$label: $start$unit"
            sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    tv.text = "$label: ${p + min}$unit"
                    set(p + min)
                    applyRest()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
            box.addView(tv); box.addView(sb)
            resets.add { sb.progress = def - min }
        }
        add("Intensidad del filtro", 0, 100, restA, 30, "%") { restA = it }
        add("Brillo de la pantalla", 5, 100, restB, 30, "%") { restB = it }
        add("Tono (amarillo a rojizo)", 0, 100, restT, 50, "") { restT = it }

        val dlg = AlertDialog.Builder(this)
            .setTitle("Descanso visual")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Listo", null)
            .setNeutralButton("Restablecer", null)
            .setNegativeButton("Apagar") { _, _ -> setRest(false) }
            .create()
        dlg.setOnDismissListener { Store.setRestCfg(this, restA, restB, restT) }
        dlg.show()
        dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { resets.forEach { it() } }
    }

    // ---------- Modo puntero (cursor virtual controlado con las flechas) ----------
    private fun setPointerMode(on: Boolean, announce: Boolean = false) {
        pointerMode = on
        Store.setPointer(this, on)
        if (!on && ptrDown) { sendTouch(MotionEvent.ACTION_CANCEL); ptrDown = false }
        applyPointer()
        if (!on) b.btnPtr.requestFocusFromTouch()
        if (announce) toast(
            if (on) "Modo puntero: flechas mueven el cursor, OK hace clic. Para volver: mantén presionado ATRÁS (o usa el botón \"Modo puntero\")."
            else "Modo control normal"
        )
    }

    private fun applyPointer() {
        b.pointer.visibility = if (pointerMode) View.VISIBLE else View.GONE
        b.btnPtr.text = if (pointerMode) "Modo puntero" else "Modo control"
        chip(b.btnPtr, pointerMode)
        if (pointerMode) wakeCursor()
    }

    private fun wakeCursor() {
        h.removeCallbacks(hideCursor)
        b.pointer.alpha = 1f
        if (fullscreen) h.postDelayed(hideCursor, 4000)
    }

    private fun sendTouch(action: Int) {
        val loc = IntArray(2)
        b.pointer.getLocationInWindow(loc)
        val now = SystemClock.uptimeMillis()
        val ev = MotionEvent.obtain(ptrDownTime, now, action, curX + loc[0], curY + loc[1], 0)
        ev.source = InputDevice.SOURCE_TOUCHSCREEN
        dispatchTouchEvent(ev)
        ev.recycle()
    }

    /** Devuelve true si la tecla fue consumida por el modo puntero. */
    private fun handlePointerKey(e: KeyEvent): Boolean {
        var dx = 0; var dy = 0
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> dx = -1
            KeyEvent.KEYCODE_DPAD_RIGHT -> dx = 1
            KeyEvent.KEYCODE_DPAD_UP -> dy = -1
            KeyEvent.KEYCODE_DPAD_DOWN -> dy = 1
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) {
                    ptrDownTime = SystemClock.uptimeMillis()
                    ptrDown = true
                    sendTouch(MotionEvent.ACTION_DOWN)
                } else if (e.action == KeyEvent.ACTION_UP && ptrDown) {
                    ptrDown = false
                    sendTouch(MotionEvent.ACTION_UP)
                }
                wakeCursor()
                return true
            }
            else -> return false
        }
        if (e.action == KeyEvent.ACTION_DOWN) {
            val d = resources.displayMetrics.density
            val step = (8f + minOf(e.repeatCount, 30) * 1.2f) * d   // acelera al mantener presionado
            moveCursor(dx * step, dy * step)
        }
        return true
    }

    private fun moveCursor(dx: Float, dy: Float) {
        val w = b.pointer.width.toFloat(); val hh = b.pointer.height.toFloat()
        val nx = (curX + dx).coerceIn(0f, w - 1f)
        val ny = (curY + dy).coerceIn(0f, hh - 1f)
        val overX = (curX + dx) - nx
        val overY = (curY + dy) - ny
        curX = nx; curY = ny
        b.pointer.setPos(curX, curY)
        wakeCursor()
        // Si el cursor choca con el borde, se desplaza la lista más cercana (como la rueda del mouse)
        if (overX != 0f || overY != 0f) scrollNearest(overX.toInt() * 2, overY.toInt() * 2)
    }

    private fun scrollNearest(sx: Int, sy: Int) {
        val loc = IntArray(2)
        b.pointer.getLocationInWindow(loc)
        val x = curX + loc[0]; val y = curY + loc[1]
        var best: View? = null
        var bestD = Float.MAX_VALUE
        fun visit(v: View) {
            if (v.visibility != View.VISIBLE) return
            if (v is RecyclerView || v is HorizontalScrollView) {
                val l = IntArray(2); v.getLocationInWindow(l)
                val ddx = maxOf(l[0] - x, 0f, x - (l[0] + v.width))
                val ddy = maxOf(l[1] - y, 0f, y - (l[1] + v.height))
                val dist = ddx * ddx + ddy * ddy
                if (dist < bestD) { bestD = dist; best = v }
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) visit(v.getChildAt(i))
        }
        visit(b.root)
        best?.scrollBy(sx, sy)
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (pointerMode && handlePointerKey(e)) return true
        return super.dispatchKeyEvent(e)
    }

    // ---------- Control remoto (Smart TV) ----------
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (b.overlayList.visibility == View.VISIBLE) scheduleHideOverlay()
        val bare = fullscreen && b.overlayList.visibility != View.VISIBLE
        when (keyCode) {
            KeyEvent.KEYCODE_PROG_GREEN, KeyEvent.KEYCODE_PROG_BLUE -> {
                if (event.repeatCount == 0) setPointerMode(!pointerMode, announce = true)
                return true
            }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_GUIDE, KeyEvent.KEYCODE_PROG_RED -> {
                if (event.repeatCount == 0) setFullscreen(!fullscreen)
                return true
            }
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_MEDIA_NEXT -> { step(1); return true }
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { step(-1); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (bare) {
                showOverlay(); showControls(); return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> if (bare) { step(-1); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> if (bare) { step(1); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && pointerMode) {
            setPointerMode(false, announce = true)
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            b.overlayList.visibility == View.VISIBLE -> hideOverlayNow()
            fullscreen -> setFullscreen(false)
            else -> confirmExit()
        }
    }

    private var exitDialog: AlertDialog? = null

    private fun confirmExit() {
        if (exitDialog?.isShowing == true) return
        exitDialog = AlertDialog.Builder(this)
            .setTitle("Salir")
            .setMessage("¿Quieres salir de la app?")
            .setPositiveButton("Salir") { _, _ -> finish() }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}
