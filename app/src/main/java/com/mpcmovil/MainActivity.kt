package com.mpcmovil

import android.app.AlertDialog
import android.content.Intent
import android.content.res.AssetFileDescriptor
import android.media.MediaMetadataRetriever
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.wifi.WifiManager
import android.os.Environment
import android.provider.MediaStore
import android.view.TextureView
import android.view.ViewGroup
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.os.Build
import android.database.Cursor
import android.provider.OpenableColumns
import android.util.Log
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import org.videolan.libvlc.*
import org.videolan.libvlc.util.VLCVideoLayout
import java.util.Locale
import java.text.SimpleDateFormat
import java.util.Date
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var videoLayout: VLCVideoLayout
    private lateinit var welcomeScreen: View
    private lateinit var libraryItems: LinearLayout
    private lateinit var libraryHeading: TextView
    private lateinit var librarySubtitle: TextView
    private var libraryMode = "Videos"
    private var librarySearchQuery = ""
    private var selectedFolder = ""
    private val libraryVideos = mutableListOf<LibraryVideo>()
    private data class LibraryVideo(val uri: Uri, val name: String, val duration: Long, val folder: String)
    private lateinit var topBar: View
    private lateinit var bottomBar: View
    private lateinit var centerControls: View
    private lateinit var btnCenterPlay: ImageButton
    private lateinit var txtStatus: TextView
    private lateinit var txtDecoderInfo: TextView
    private lateinit var txtTime: TextView
    private lateinit var txtRemaining: TextView
    private lateinit var txtOsd: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var btnPlay: ImageButton
    private lateinit var btnHwSw: Button
    private lateinit var btnSpeed: Button

    private var libVLC: LibVLC? = null
    private var mediaPlayer: MediaPlayer? = null
    private var currentUri: Uri? = null
    private val playlist = mutableListOf<Uri>()
    private var playlistIndex = -1
    private var currentAssetDescriptor: AssetFileDescriptor? = null
    private var viewsAttached = false
    private var playbackDiagnostics = ""
    private var playbackEvents = mutableListOf<String>()
    private var isHw = true
    private var isControlsVisible = true
    private var isLibraryVisible = true
    private val controlsHideRunnable = Runnable {
        if (mediaPlayer?.isPlaying == true) setControlsVisible(false)
    }
    private var immersiveMode = false
    private var subtitlePickerPending = false
    private var resumeAfterSubtitlePicker = false
    private var filePickerPending = false
    private var resumeAfterFilePicker = false
    private var displayUnderNotch = true
    private var aspectIndex = 0
    private var rotationMode = 0
    private val speeds = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
    private var speedIndex = 2
    private val aspects = arrayOf("Original / automático", "16:9", "4:3", "1:1", "2.35:1", "Rellenar")
    private val aspectValues = arrayOf<String?>(null, "16:9", "4:3", "1:1", "2.35:1", "fill")

    private val handler = Handler(Looper.getMainLooper())
    private val thumbnailExecutor = Executors.newSingleThreadExecutor()
    private val playbackExecutor = Executors.newSingleThreadExecutor()
    private val playbackRequestId = java.util.concurrent.atomic.AtomicInteger(0)
    private var lastProgressSaveAt = 0L
    private var isSeeking = false
    private lateinit var gestureDetector: GestureDetector
    private var gestureStartVolume = 0
    private var gestureStartBrightness = 0.5f
    private var gestureStartTime = 0L
    private var gestureMode = ""
    private var repeatA: Long? = null
    private var repeatB: Long? = null
    // 0 = normal, 1 = repetir lista, 2 = repetir vídeo, 3 = aleatorio.
    private var playbackMode = 0
    private var sleepTimerRunnable: Runnable? = null
    private val rendererDiscoverers = mutableListOf<RendererDiscoverer>()
    private val rendererItems = mutableListOf<RendererItem>()
    private var multicastLock: WifiManager.MulticastLock? = null
    private var castDialog: AlertDialog? = null
    private var activeRendererName: String? = null
    private val screenshotRequestCode = 9041
    private var seekIntervalSeconds = 5
    private lateinit var btnScreenshotFloat: Button

    private val fileLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        val data = res.data
        val selected = mutableListOf<Uri>()
        if (res.resultCode == RESULT_OK && data != null) {
            data.clipData?.let { clip -> for (i in 0 until clip.itemCount) selected.add(clip.getItemAt(i).uri) }
            if (selected.isEmpty()) data.data?.let { selected.add(it) }
        }
        val shouldResume = resumeAfterFilePicker
        filePickerPending = false
        resumeAfterFilePicker = false
        videoLayout.postDelayed({
            restoreVideoOutput(selected.isEmpty() && shouldResume)
            if (selected.isNotEmpty()) {
                selected.forEach { persistReadPermission(it, data?.flags ?: 0) }
                playlist.clear(); playlist.addAll(selected); playlistIndex = 0
                showOsd("Lista: " + playlist.size + " archivos"); play(playlist[0])
            }
        }, 300)
    }

    private val subtitleLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        val uri = if (res.resultCode == RESULT_OK) res.data?.data else null
        finishSubtitlePicker()
        if (uri != null) {
            persistReadPermission(uri, res.data?.flags ?: 0)
            attachExternalSubtitle(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        displayUnderNotch = getSharedPreferences("mpm_player", MODE_PRIVATE)
            .getBoolean("display_under_notch", true)
        applyDisplayUnderNotch()
        showSystemBars()
        applyPreferredRefreshRate()
        initViews()
        initGestures()
        initPlayer()
        renderLibrary("Videos")
        ensureLibraryPermission()
        intent?.data?.let { play(it) }
    }


    private fun showAudioSubsPanel(initial: String) {
        val dialog = android.app.Dialog(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(12))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF151B24.toInt()); cornerRadius = dp(22).toFloat()
                setStroke(dp(1), 0xFF303B49.toInt())
            }
        }
        val header = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL; orientation = LinearLayout.HORIZONTAL }
        val title = TextView(this).apply {
            text = if (initial == "Subs") "Subtítulos" else "Audio"
            setTextColor(0xFFFFFFFF.toInt()); textSize = 20f; setTypeface(null, android.graphics.Typeface.BOLD)
        }
        header.addView(title, LinearLayout.LayoutParams(0, dp(48), 1f))
        val close = TextView(this).apply {
            text = "✕"; textSize = 20f; gravity = android.view.Gravity.CENTER
            setTextColor(0xFFDDDDDD.toInt()); setPadding(dp(12), dp(8), dp(8), dp(8))
            setOnClickListener { dialog.dismiss() }
        }
        header.addView(close, LinearLayout.LayoutParams(dp(44), dp(48)))
        root.addView(header)
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, dp(10)) }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(tabs)
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(body, android.widget.FrameLayout.LayoutParams(-1, -2))
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val footer = TextView(this).apply {
            text = "Pistas gestionadas por LibVLC · motor de VLC"
            textSize = 11f; setTextColor(0xFF9BA8B8.toInt())
            setPadding(dp(4), dp(12), dp(4), dp(4))
        }
        root.addView(footer)
        var currentTab = if (initial == "Subs") "Subs" else "Audio"
        fun row(label: String, selected: Boolean = false, action: () -> Unit) {
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(14), 0, dp(14), 0)
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(if (selected) 0xFF3A281D.toInt() else 0xFF202936.toInt())
                    cornerRadius = dp(12).toFloat()
                }
                isClickable = true; isFocusable = true; setOnClickListener { action() }
            }
            val labelView = TextView(this).apply {
                text = label; textSize = 14f; setTextColor(if (selected) 0xFFFF8738.toInt() else 0xFFF1F4F8.toInt())
            }
            item.addView(labelView, LinearLayout.LayoutParams(0, -1, 1f))
            if (selected) item.addView(TextView(this).apply {
                text = "✓"; textSize = 18f; setTextColor(0xFFFF6A00.toInt()); gravity = android.view.Gravity.CENTER
            }, LinearLayout.LayoutParams(dp(28), -1))
            body.addView(item, LinearLayout.LayoutParams(-1, dp(50)).apply { bottomMargin = dp(7) })
        }
        fun render(tab: String) {
            currentTab = tab
            title.text = if (tab == "Subs") "Subtítulos" else "Audio"
            body.removeAllViews()
            if (tab == "Audio") {
                val tracks = mediaPlayer?.audioTracks.orEmpty()
                if (tracks.isEmpty()) row("No hay pistas de audio disponibles") {}
                tracks.forEach { track ->
                    row(track.name ?: "Pista ${track.id}", track.id == (mediaPlayer?.audioTrack ?: -1)) {
                        mediaPlayer?.audioTrack = track.id
                        showOsd("Pista de audio cambiada")
                        dialog.dismiss()
                    }
                }
            } else {
                row("Desactivar subtítulos", (mediaPlayer?.spuTrack ?: -1) == -1) {
                    mediaPlayer?.spuTrack = -1
                    showOsd("Subtítulos desactivados")
                    dialog.dismiss()
                }
                val tracks = mediaPlayer?.spuTracks.orEmpty()
                if (tracks.isEmpty()) row("Buscando pistas de subtítulos…") {}
                tracks.forEach { track ->
                    row(track.name ?: "Subtítulo ${track.id}", track.id == (mediaPlayer?.spuTrack ?: -1)) {
                        mediaPlayer?.spuTrack = track.id
                        showOsd("Subtítulo seleccionado")
                        dialog.dismiss()
                    }
                }
                row("Cargar archivo externo…") { dialog.dismiss(); pickExternalSubtitle() }
                row("Sincronización de subtítulos…") { dialog.dismiss(); showSubtitleDelayDialog() }
            }
            tabs.removeAllViews()
            listOf("Audio", "Subs").forEach { tabName ->
                val active = tabName == tab
                tabs.addView(TextView(this).apply {
                    text = if (tabName == "Subs") "SUBTÍTULOS" else "AUDIO"
                    gravity = android.view.Gravity.CENTER
                    textSize = 12f; setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(if (active) 0xFFFF8738.toInt() else 0xFF9BA8B8.toInt())
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(if (active) 0xFF3A281D.toInt() else 0xFF202936.toInt())
                        cornerRadius = dp(10).toFloat()
                    }
                    setOnClickListener { render(tabName) }
                }, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                    if (tabName == "Audio") marginEnd = dp(6) else marginStart = dp(6)
                })
            }
        }
        render(currentTab)
        dialog.setContentView(root)
        dialog.show()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0x00000000))
        dialog.window?.setLayout(minOf(dp(380), resources.displayMetrics.widthPixels - dp(24)), -1)
        dialog.window?.setGravity(android.view.Gravity.END)
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        dialog.window?.attributes = dialog.window?.attributes?.apply { dimAmount = 0.58f }
        if (currentTab == "Subs") {
            fun refresh(attempt: Int) {
                handler.postDelayed({
                    if (dialog.isShowing && currentTab == "Subs" && attempt < 12) {
                        render("Subs")
                        refresh(attempt + 1)
                    }
                }, 400L)
            }
            refresh(0)
        }
    }

    private fun showSubtitleDelayDialog() {
        val dialog = android.app.Dialog(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(18), dp(22), dp(18))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF151B24.toInt()); cornerRadius = dp(20).toFloat()
                setStroke(dp(1), 0xFF303B49.toInt())
            }
        }
        root.addView(TextView(this).apply {
            text = "Sincronización de subtítulos"
            textSize = 19f; setTextColor(0xFFFFFFFF.toInt())
            setTypeface(null, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(-1, dp(48)))
        val value = TextView(this).apply {
            gravity = android.view.Gravity.CENTER
            textSize = 25f; setTextColor(0xFFFF8738.toInt())
        }
        fun refreshValue() {
            val micros = mediaPlayer?.getSpuDelay() ?: 0L
            value.text = String.format(Locale.US, "%+.1f s", micros / 1_000_000.0)
        }
        root.addView(value, LinearLayout.LayoutParams(-1, dp(66)))
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER }
        fun control(label: String, action: () -> Unit) {
            controls.addView(TextView(this).apply {
                text = label; gravity = android.view.Gravity.CENTER; textSize = 19f
                setTextColor(0xFFFFFFFF.toInt())
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(0xFF202936.toInt()); cornerRadius = dp(12).toFloat()
                }
                setOnClickListener { action(); refreshValue() }
            }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(5); marginStart = dp(5) })
        }
        control("−0.1") { mediaPlayer?.let { it.setSpuDelay(it.getSpuDelay() - 100_000L) } }
        control("＋0.1") { mediaPlayer?.let { it.setSpuDelay(it.getSpuDelay() + 100_000L) } }
        root.addView(controls, LinearLayout.LayoutParams(-1, dp(54)))
        val reset = TextView(this).apply {
            text = "RESTABLECER"; gravity = android.view.Gravity.CENTER
            setTextColor(0xFFFF8A3D.toInt()); textSize = 12f
            setOnClickListener { mediaPlayer?.setSpuDelay(0L); refreshValue() }
        }
        root.addView(reset, LinearLayout.LayoutParams(-1, dp(46)))
        val close = TextView(this).apply {
            text = "CERRAR"; gravity = android.view.Gravity.CENTER
            setTextColor(0xFFB6C1CE.toInt()); textSize = 12f
            setOnClickListener { dialog.dismiss() }
        }
        root.addView(close, LinearLayout.LayoutParams(-1, dp(42)))
        refreshValue()
        dialog.setContentView(root)
        dialog.show()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0x00000000))
        dialog.window?.setLayout(-1, -2)
        dialog.window?.setGravity(android.view.Gravity.BOTTOM)
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        dialog.window?.attributes = dialog.window?.attributes?.apply { dimAmount = 0.58f }
    }

    private fun showImagePanel() {
        val items = arrayOf("Relación de aspecto", "Cambiar orientación", "Rellenar pantalla", "Alternar video bajo notch")
        AlertDialog.Builder(this).setTitle("Imagen y pantalla").setItems(items) { _, which ->
            when (which) {
                0 -> showAspectMenu()
                1 -> cycleRotation()
                2 -> applyAspect(aspects.lastIndex)
                3 -> {
                    displayUnderNotch = !displayUnderNotch
                    getSharedPreferences("mpm_player", MODE_PRIVATE).edit()
                        .putBoolean("display_under_notch", displayUnderNotch).apply()
                    applyDisplayUnderNotch()
                }
            }
        }.setNegativeButton("Cerrar", null).show()
    }

    private fun showPlaybackTools() {
        val items = arrayOf("Ir a un tiempo…", "Temporizador de apagado", "Marcar punto A", "Marcar punto B y repetir", "Desactivar repetición A-B", "Velocidad", "Modo de reproducción: ${playbackModeLabel()}")
        AlertDialog.Builder(this).setTitle("Herramientas de reproducción").setItems(items) { _, which ->
            when (which) {
                0 -> showJumpToTime()
                1 -> showSleepTimer()
                2 -> {
                    repeatA = mediaPlayer?.time
                    repeatB = null
                    showOsd(if (repeatA == null) "Abre un video primero" else "Punto A marcado")
                }
                3 -> {
                    val now = mediaPlayer?.time
                    if (repeatA == null || now == null || now <= (repeatA ?: 0L)) {
                        Toast.makeText(this, "Marca primero un punto A válido", Toast.LENGTH_SHORT).show()
                    } else {
                        repeatB = now
                        showOsd("Repetición A-B activada")
                    }
                }
                4 -> { repeatA = null; repeatB = null; showOsd("Repetición A-B desactivada") }
                5 -> showSpeedMenu()
                6 -> showPlaybackModeMenu()
            }
        }.setNegativeButton("Cerrar", null).show()
    }

    private fun playbackModeLabel(): String = when (playbackMode) {
        1 -> "Repetir lista"
        2 -> "Repetir vídeo"
        3 -> "Aleatorio"
        else -> "Normal"
    }

    private fun showPlaybackModeMenu() {
        val labels = arrayOf("Normal", "Repetir toda la lista", "Repetir el vídeo actual", "Aleatorio")
        AlertDialog.Builder(this).setTitle("Modo de reproducción")
            .setSingleChoiceItems(labels, playbackMode) { dialog, which ->
                playbackMode = which
                getSharedPreferences("mpm_player", MODE_PRIVATE).edit()
                    .putInt("playback_mode", playbackMode).apply()
                showOsd("Modo: ${playbackModeLabel()}")
                dialog.dismiss()
            }
            .setNegativeButton("Cerrar", null).show()
    }

    private fun showSpeedMenu() {
        val labels = speeds.map { it.toString() + "×" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Velocidad")
            .setSingleChoiceItems(labels, speedIndex) { d, which ->
                speedIndex = which
                val value = speeds[which]
                mediaPlayer?.rate = value
                btnSpeed.text = value.toString() + "X"
                showOsd("Velocidad: " + value + "×")
                d.dismiss()
            }.setNegativeButton("Cerrar", null).show()
    }

    private fun showJumpToTime() {
        val field = EditText(this).apply { hint = "HH:MM:SS o segundos;"; setSingleLine(true) }
        AlertDialog.Builder(this).setTitle("Ir a un tiempo")
            .setMessage("Ejemplo: 00:12:30")
            .setView(field)
            .setPositiveButton("Ir") { _, _ ->
                val parts = field.text.toString().trim().split(":")
                val seconds = try {
                    when (parts.size) {
                        1 -> parts[0].toLong()
                        2 -> parts[0].toLong() * 60L + parts[1].toLong()
                        3 -> parts[0].toLong() * 3600L + parts[1].toLong() * 60L + parts[2].toLong()
                        else -> -1L
                    }
                } catch (_: Exception) { -1L }
                if (seconds < 0L) Toast.makeText(this, "Tiempo no válido", Toast.LENGTH_SHORT).show()
                else mediaPlayer?.let { p ->
                    val target = seconds * 1000L
                    p.time = if (p.length > 0L) target.coerceAtMost(p.length) else target
                    showOsd("Salto realizado")
                } ?: Toast.makeText(this, "Primero abre un archivo", Toast.LENGTH_SHORT).show()
            }.setNegativeButton("Cancelar", null).show()
    }

    private fun showSleepTimer() {
        val choices = arrayOf("15 minutos", "30 minutos", "45 minutos", "60 minutos", "Cancelar temporizador")
        AlertDialog.Builder(this).setTitle("Temporizador").setItems(choices) { _, which ->
            sleepTimerRunnable?.let { handler.removeCallbacks(it) }
            sleepTimerRunnable = null
            if (which == 4) showOsd("Temporizador cancelado")
            else {
                val minutes = intArrayOf(15, 30, 45, 60)[which]
                val task = Runnable {
                    mediaPlayer?.pause()
                    showOsd("Temporizador finalizado")
                    sleepTimerRunnable = null
                }
                sleepTimerRunnable = task
                handler.postDelayed(task, minutes * 60_000L)
                showOsd("Pausa programada: " + minutes + " min")
            }
        }.setNegativeButton("Cerrar", null).show()
    }

    private fun showPlayerSettings() {
        val dialog = android.app.Dialog(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(16))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF151B24.toInt()); cornerRadius = dp(22).toFloat()
                setStroke(dp(1), 0xFF303B49.toInt())
            }
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "Ajustes"
            textSize = 22f; setTextColor(0xFFFFFFFF.toInt())
            setTypeface(null, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, dp(52), 1f))
        header.addView(TextView(this).apply {
            text = "✕"; textSize = 20f; gravity = android.view.Gravity.CENTER
            setTextColor(0xFFDDDDDD.toInt())
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(dp(42), dp(48)))
        root.addView(header)
        root.addView(TextView(this).apply {
            text = "MPM PLAYER  ·  CONFIGURACIÓN"
            textSize = 10f; letterSpacing = 0.12f
            setTextColor(0xFFFF8738.toInt())
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(dp(4), dp(4), 0, dp(14))
        })
        val scroll = ScrollView(this)
        val items = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(items)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        fun section(label: String) {
            items.addView(TextView(this).apply {
                text = label.uppercase(Locale.getDefault())
                textSize = 10f; letterSpacing = 0.1f
                setTextColor(0xFFFF8738.toInt())
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(dp(4), dp(12), 0, dp(8))
            }, LinearLayout.LayoutParams(-1, dp(34)))
        }
        fun action(label: String, detail: String, callback: () -> Unit) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(8), dp(12), dp(8))
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(0xFF202936.toInt()); cornerRadius = dp(13).toFloat()
                    setStroke(dp(1), 0xFF2D3948.toInt())
                }
                isClickable = true; isFocusable = true
                setOnClickListener { dialog.dismiss(); callback() }
            }
            val labels = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            labels.addView(TextView(this).apply {
                text = label; textSize = 14f; setTextColor(0xFFF5F7FA.toInt())
                setTypeface(null, android.graphics.Typeface.BOLD)
                maxLines = 1
            })
            labels.addView(TextView(this).apply {
                text = detail; textSize = 11f; setTextColor(0xFF9BA8B8.toInt())
                maxLines = 2
            })
            row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(TextView(this).apply {
                text = "›"; textSize = 25f; gravity = android.view.Gravity.CENTER
                setTextColor(0xFFFF8738.toInt())
            }, LinearLayout.LayoutParams(dp(24), dp(42)))
            items.addView(row, LinearLayout.LayoutParams(-1, dp(64)).apply {
                bottomMargin = dp(7)
            })
        }

        section("Reproducción")
        action("Audio", "Elegir pista de sonido") { showAudioSubsPanel("Audio") }
        action("Subtítulos", "Pistas VLC, archivos externos y sincronización") { showAudioSubsPanel("Subs") }
        action("Velocidad y herramientas", "Velocidad, salto, repetición A-B y temporizador") { showPlaybackTools() }
        action("Decodificación", if (isHw) "Aceleración por hardware activada" else "Decodificación por software activada") {
            btnHwSw.performClick()
        }
        action("Intervalo de salto", "Doble toque: ${seekIntervalSeconds} segundos") { showSeekIntervalDialog() }

        section("Pantalla e interfaz")
        action("Imagen y pantalla", "Relación de aspecto, orientación y notch") { showImagePanel() }
        action("Vídeo bajo el notch", if (displayUnderNotch) "Activado" else "Desactivado") {
            displayUnderNotch = !displayUnderNotch
            getSharedPreferences("mpm_player", MODE_PRIVATE).edit()
                .putBoolean("display_under_notch", displayUnderNotch).apply()
            applyDisplayUnderNotch()
            showOsd(if (displayUnderNotch) "Vídeo bajo el notch activado" else "Vídeo bajo el notch desactivado")
        }
        val floatingScreenshot = getSharedPreferences("mpm_player", MODE_PRIVATE)
            .getBoolean("floating_screenshot", true)
        action("Botón flotante de captura", if (floatingScreenshot) "Visible durante la reproducción" else "Oculto") {
            toggleFloatingScreenshot()
        }
        action("Controles del reproductor", "Mostrar u ocultar la barra de reproducción") {
            setControlsVisible(!isControlsVisible)
        }

        section("Biblioteca")
        action("Volver a la biblioteca", "Vídeos, carpetas, recientes y favoritos") {
            welcomeScreen.visibility = View.VISIBLE
            setControlsVisible(false)
            renderLibrary("Videos")
            showSystemBars()
        }
        dialog.setContentView(root)
        dialog.show()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0x00000000))
        dialog.window?.setLayout(minOf(dp(390), resources.displayMetrics.widthPixels - dp(24)), -1)
        dialog.window?.setGravity(android.view.Gravity.END)
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        dialog.window?.attributes = dialog.window?.attributes?.apply { dimAmount = 0.58f }
    }

    private fun showCastDialog() {
        if (currentUri == null) {
            Toast.makeText(this, "Primero abre un video", Toast.LENGTH_SHORT).show()
            return
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(12))
            setBackgroundColor(0xFF151B24.toInt())
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val status = TextView(this).apply {
            text = "Buscando dispositivos compatibles en tu red…"
            setTextColor(0xFFB9C4D0.toInt())
            textSize = 13f
            setPadding(0, dp(8), dp(8), dp(12))
        }
        header.addView(status, LinearLayout.LayoutParams(0, -2, 1f))
        val refresh = Button(this).apply {
            text = "↻"
            textSize = 20f
            isAllCaps = false
            minWidth = dp(44)
            minHeight = dp(42)
            setTextColor(0xFFE7EDF5.toInt())
            setBackgroundColor(0xFF252E39.toInt())
        }
        header.addView(refresh, LinearLayout.LayoutParams(dp(48), dp(42)))
        root.addView(header)

        val names = mutableListOf<String>()
        val listView = ListView(this).apply {
            divider = android.graphics.drawable.ColorDrawable(0xFF29323D.toInt())
            dividerHeight = dp(1)
            cacheColorHint = android.graphics.Color.TRANSPARENT
            isVerticalScrollBarEnabled = false
        }
        val empty = TextView(this).apply {
            text = "Buscando dispositivos…"
            gravity = android.view.Gravity.CENTER
            setTextColor(0xFF8F9BA9.toInt())
            textSize = 13f
            setPadding(dp(20), dp(28), dp(20), dp(28))
        }
        val adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, names) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val deviceName = getItem(position).orEmpty()
                val row = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(0xFF202936.toInt())
                        cornerRadius = dp(10).toFloat()
                    }
                }
                val icon = TextView(this@MainActivity).apply {
                    text = "▣"
                    textSize = 22f
                    gravity = android.view.Gravity.CENTER
                    setTextColor(0xFFFF8738.toInt())
                }
                row.addView(icon, LinearLayout.LayoutParams(dp(42), dp(48)))
                val labels = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }
                labels.addView(TextView(this@MainActivity).apply {
                    text = deviceName
                    textSize = 14f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(0xFFF0F3F7.toInt())
                    setTypeface(null, android.graphics.Typeface.BOLD)
                })
                labels.addView(TextView(this@MainActivity).apply {
                    text = "Reproducción remota mediante LibVLC"
                    textSize = 11f
                    setTextColor(0xFF9BA8B8.toInt())
                })
                row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(TextView(this@MainActivity).apply {
                    text = "›"
                    textSize = 25f
                    setTextColor(0xFF9AA7B5.toInt())
                })
                return row
            }
        }
        listView.adapter = adapter
        root.addView(listView, LinearLayout.LayoutParams(-1, dp(300)))
        root.addView(empty, LinearLayout.LayoutParams(-1, dp(300)))
        listView.emptyView = empty

        val dialog = AlertDialog.Builder(this)
            .setTitle("Reproductor de red compatible")
            .setView(root)
            .setNeutralButton("Desconectar") { _, _ -> disconnectRenderer() }
            .setNegativeButton("Cerrar", null)
            .create()
        castDialog = dialog
        dialog.setOnDismissListener {
            stopRendererDiscovery()
            if (castDialog === dialog) castDialog = null
        }

        fun refreshDevices() {
            stopRendererDiscovery()
            names.clear()
            adapter.notifyDataSetChanged()
            empty.text = "Buscando dispositivos…"
            status.text = "Buscando en la red local…"
            startRendererDiscovery(status, adapter, names)
        }
        refresh.setOnClickListener { refreshDevices() }

        listView.setOnItemClickListener { _, _, position, _ ->
            val item = rendererItems.getOrNull(position)
            val player = mediaPlayer
            if (item == null) return@setOnItemClickListener
            if (player == null) {
                Toast.makeText(this, "El reproductor no está disponible", Toast.LENGTH_SHORT).show()
                return@setOnItemClickListener
            }
            val wasPlaying = player.isPlaying
            status.text = "Conectando con " + item.displayName + "…"
            listView.isEnabled = false
            refresh.isEnabled = false
            try {
                if (wasPlaying) player.pause()
                val result = player.setRenderer(item)
                if (result == 0) {
                    activeRendererName = item.displayName
                    player.play()
                    status.text = "Reproducción enviada a " + item.displayName
                    showOsd("Emitiendo en " + item.displayName)
                    dialog.dismiss()
                } else {
                    if (wasPlaying) player.play()
                    listView.isEnabled = true
                    refresh.isEnabled = true
                    status.text = "El dispositivo rechazó la conexión. Prueba otro o vuelve a buscar."
                }
            } catch (e: Exception) {
                Log.e("MPM_PLAYER", "Error al conectar con el dispositivo de emisión", e)
                if (wasPlaying) {
                    try { player.play() } catch (_: Exception) {}
                }
                listView.isEnabled = true
                refresh.isEnabled = true
                status.text = "No se pudo conectar. Comprueba que ambos estén en la misma red Wi-Fi."
            }
        }
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        startRendererDiscovery(status, adapter, names)
    }

    private fun startRendererDiscovery(status: TextView, adapter: ArrayAdapter<String>, names: MutableList<String>) {
        stopRendererDiscovery()
        val wifi = applicationContext.getSystemService(WIFI_SERVICE) as? WifiManager
        try {
            multicastLock = wifi?.createMulticastLock("MPMPlayerRendererDiscovery")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.w("MPM_PLAYER", "No se pudo adquirir el bloqueo multicast", e)
        }
        val vlc = libVLC
        if (vlc == null) {
            status.text = "El motor VLC no está disponible"
            return
        }
        try {
            val descriptions = RendererDiscoverer.list(vlc)
            if (descriptions.isEmpty()) {
                status.text = "Este motor VLC no ofrece servicios de descubrimiento en este dispositivo"
                return
            }
            var started = 0
            descriptions.forEach { description ->
                val discoverer = RendererDiscoverer(vlc, description.name)
                discoverer.setEventListener { event ->
                    val item = event.item
                    runOnUiThread {
                        when (event.type) {
                            RendererDiscoverer.Event.ItemAdded -> {
                                if (rendererItems.none { it.name == item.name }) {
                                    rendererItems.add(item)
                                    names.add(item.displayName)
                                    adapter.notifyDataSetChanged()
                                }
                                status.text = if (names.isEmpty()) "Buscando dispositivos…" else "Dispositivos encontrados: " + names.size
                            }
                            RendererDiscoverer.Event.ItemDeleted -> {
                                val index = rendererItems.indexOfFirst { it.name == item.name }
                                if (index >= 0) {
                                    rendererItems.removeAt(index)
                                    if (index < names.size) names.removeAt(index)
                                    adapter.notifyDataSetChanged()
                                }
                                status.text = if (names.isEmpty()) "Buscando dispositivos compatibles…" else "Dispositivos encontrados: " + names.size
                            }
                        }
                    }
                }
                try {
                    if (discoverer.start()) {
                        rendererDiscoverers.add(discoverer)
                        started++
                    } else {
                        discoverer.setEventListener(null)
                        discoverer.release()
                    }
                } catch (e: Exception) {
                    discoverer.setEventListener(null)
                    discoverer.release()
                    Log.w("MPM_PLAYER", "No se pudo iniciar el descubrimiento " + description.name, e)
                }
            }
            if (started == 0) status.text = "No se pudo iniciar la búsqueda de dispositivos"
            else {
                status.text = "Buscando televisores y dispositivos compatibles en la misma red…"
                handler.postDelayed({
                    if (dialogIsShowing(status) && names.isEmpty()) {
                        status.text = "No se encontraron reproductores compatibles. Comprueba la misma red Wi‑Fi."
                    }
                }, 8000)
            }
        } catch (e: Exception) {
            Log.e("MPM_PLAYER", "Error al buscar dispositivos de emisión", e)
            status.text = "No se pudo buscar dispositivos en esta red"
        }
    }

    private fun dialogIsShowing(status: TextView): Boolean =
        castDialog?.isShowing == true && status.isShown

    private fun stopRendererDiscovery() {
        rendererDiscoverers.forEach { discoverer ->
            try {
                discoverer.stop()
                discoverer.setEventListener(null)
                discoverer.release()
            } catch (e: Exception) {
                Log.w("MPM_PLAYER", "Error al detener el descubrimiento de dispositivos", e)
            }
        }
        rendererDiscoverers.clear()
        rendererItems.clear()
        try {
            multicastLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            Log.w("MPM_PLAYER", "No se pudo liberar el bloqueo multicast", e)
        } finally {
            multicastLock = null
        }
    }

    private fun disconnectRenderer() {
        try {
            mediaPlayer?.let { player ->
                if (player.isPlaying) player.pause()
                player.setRenderer(null)
                player.play()
            }
            activeRendererName = null
            showOsd("Emisión desconectada")
        } catch (e: Exception) {
            Log.e("MPM_PLAYER", "No se pudo desconectar el dispositivo de emisión", e)
            Toast.makeText(this, "No se pudo desconectar el dispositivo", Toast.LENGTH_LONG).show()
        }
    }

    private fun takeScreenshot() {
        val uri = currentUri
        if (uri == null || mediaPlayer == null) {
            Toast.makeText(this, "Primero abre un video", Toast.LENGTH_SHORT).show()
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), screenshotRequestCode)
            return
        }
        val timestampUs = ((mediaPlayer?.time ?: 0L).coerceAtLeast(0L)) * 1000L
        Toast.makeText(this, "Capturando fotograma en calidad original…", Toast.LENGTH_SHORT).show()
        thumbnailExecutor.execute {
            var retriever: MediaMetadataRetriever? = null
            var bitmap: Bitmap? = null
            try {
                retriever = MediaMetadataRetriever()
                retriever.setDataSource(this, uri)
                bitmap = retriever.getFrameAtTime(timestampUs, MediaMetadataRetriever.OPTION_CLOSEST)
            } catch (e: Exception) {
                Log.w("MPM_PLAYER", "No se pudo extraer el fotograma original; se intentará capturar la salida de vídeo", e)
            } finally {
                try { retriever?.release() } catch (_: Exception) {}
            }
            if (bitmap == null) {
                runOnUiThread {
                    Toast.makeText(this, "No se pudo extraer el fotograma completo del archivo; no se guardó una captura recortada", Toast.LENGTH_LONG).show()
                }
            } else {
                val captured = bitmap ?: return@execute
                runOnUiThread { saveScreenshotBitmap(captured, true) }
            }
        }
    }

    private fun saveScreenshotBitmap(bitmap: Bitmap, originalResolution: Boolean) {
        val fileName = "MPM_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date()) + ".jpg"
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/MPM Player")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val imageUri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("No se pudo crear el archivo de imagen")
                try {
                    val output = contentResolver.openOutputStream(imageUri)
                        ?: throw IllegalStateException("No se pudo guardar la imagen")
                    output.use { stream ->
                        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream)) {
                            throw IllegalStateException("No se pudo codificar la captura")
                        }
                    }
                    val completed = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
                    contentResolver.update(imageUri, completed, null, null)
                } catch (e: Exception) {
                    contentResolver.delete(imageUri, null, null)
                    throw e
                }
            } else {
                val folder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "MPM Player")
                if (!folder.exists() && !folder.mkdirs()) throw IllegalStateException("No se pudo crear la carpeta de capturas")
                val file = File(folder, fileName)
                FileOutputStream(file).use { stream ->
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream)) {
                        throw IllegalStateException("No se pudo codificar la captura")
                    }
                }
                MediaScannerConnection.scanFile(this, arrayOf(file.absolutePath), arrayOf("image/jpeg"), null)
            }
            val resolution = bitmap.width.toString() + "×" + bitmap.height
            showOsd("Captura guardada · " + resolution + if (originalResolution) " · resolución original" else " · captura de pantalla")
            Toast.makeText(this, "Captura guardada en Imágenes/MPM Player (" + resolution + ")", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e("MPM_PLAYER", "Error al guardar la captura", e)
            Toast.makeText(this, "No se pudo guardar la captura: " + (e.message ?: "error"), Toast.LENGTH_LONG).show()
        } finally {
            bitmap.recycle()
        }
    }

    private fun findTextureView(view: View): TextureView? {
        if (view is TextureView) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                val found = findTextureView(view.getChildAt(index))
                if (found != null) return found
            }
        }
        return null
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == screenshotRequestCode) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) takeScreenshot()
            else Toast.makeText(this, "Se necesita permiso para guardar capturas en la galería", Toast.LENGTH_LONG).show()
        } else if (requestCode == 9042) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) refreshLibrary()
            else showLibraryEmpty("Permite el acceso a vídeos para mostrar tu biblioteca")
        }
    }

    private fun showMainMenu(anchor: View) {
        showMpmMenu(anchor, "MPM PLAYER", listOf(
            "Biblioteca" to { welcomeScreen.visibility = View.VISIBLE; renderLibrary(libraryMode); showSystemBars() },
            "Abrir archivo…" to { pickFile() },
            "Emitir a dispositivo…" to { showCastDialog() },
            "Tomar captura de pantalla" to { takeScreenshot() },
            ("Botón flotante de captura: " + if (getSharedPreferences("mpm_player", MODE_PRIVATE).getBoolean("floating_screenshot", true)) "Activado" else "Desactivado") to { toggleFloatingScreenshot() },
            ("Intervalo de doble toque (" + seekIntervalSeconds + " s)") to { showSeekIntervalDialog() },
            "Lista de reproducción" to { showPlaylist() },
            "Archivos recientes" to { showRecentFiles() },
            "Favoritos" to { showFavorites() },
            "Guardar en favoritos" to { saveFavorite() },
            "Información del medio" to { showMediaInfo() },
            "Relación de aspecto" to { showAspectMenu() },
            "Cambiar orientación" to { cycleRotation() },
            "Audio y subtítulos" to { showAudioSubsPanel("Audio") },
            "Imagen y pantalla" to { showImagePanel() },
            "Herramientas de reproducción" to { showPlaybackTools() },
            "Ajustes" to { showPlayerSettings() },
            "Acerca de MPM Player" to {
                AlertDialog.Builder(this).setTitle("MPM Player")
                    .setMessage("Reproductor multimedia para Android")
                    .setPositiveButton("Aceptar", null).show()
            }
        ))
    }

    private fun showTopMenu(anchor: View, section: String) {
        val items = mutableListOf<Pair<String, () -> Unit>>()
        when (section) {
            "Archivo" -> {
                items += "Abrir video" to { pickFile() }
                items += "Abrir audio" to { pickAudio() }
                items += "Abrir subtítulo" to { pickExternalSubtitle() }
                items += "Lista de reproducción" to { showPlaylist() }
                items += "Archivos recientes" to { showRecentFiles() }
                items += "Información del medio" to { showMediaInfo() }
            }
            "Ver" -> {
                items += "Relación de aspecto" to { showAspectMenu() }
                items += "Cambiar orientación" to { cycleRotation() }
                items += (if (displayUnderNotch) "✓  Video bajo el notch" else "Video bajo el notch") to {
                    displayUnderNotch = !displayUnderNotch
                    getSharedPreferences("mpm_player", MODE_PRIVATE).edit()
                        .putBoolean("display_under_notch", displayUnderNotch).apply()
                    applyDisplayUnderNotch()
                    showOsd(if (displayUnderNotch) "Video bajo el notch activado" else "Video bajo el notch desactivado")
                }
                items += "Pantalla completa" to { enterImmersiveMode() }
                items += "Emitir a dispositivo…" to { showCastDialog() }
                items += "Tomar captura de pantalla" to { takeScreenshot() }
            }
            "Reproducir" -> {
                items += "Reproducir / Pausar" to {
                    mediaPlayer?.let { player -> if (player.isPlaying) player.pause() else player.play() }
                }
                items += "Detener" to {
                    savePlaybackProgress()
                    mediaPlayer?.stop()
                    releaseCurrentAssetDescriptor()
                    welcomeScreen.visibility = View.VISIBLE
                    renderLibrary(libraryMode)
                    txtStatus.text = "Detenido"
                }
                items += "Velocidad" to {
                    speedIndex = (speedIndex + 1) % speeds.size
                    val speed = speeds[speedIndex]
                    mediaPlayer?.rate = speed
                    btnSpeed.text = "${speed}X"
                    showOsd("Velocidad: ${speed}X")
                }
            }
            "Navegar" -> {
                items += "Retroceder 10 segundos" to {
                    mediaPlayer?.let { player -> player.time = (player.time - 10000L).coerceAtLeast(0L) }
                }
                items += "Avanzar 10 segundos" to {
                    mediaPlayer?.let { player -> player.time = (player.time + 10000L).coerceAtMost(player.length) }
                }
                items += "Pistas de audio" to { pickAudio() }
                items += "Pistas de subtítulos" to { pickSubtitles() }
            }
            "Favoritos" -> {
                items += "Guardar video actual" to { saveFavorite() }
                items += "Abrir favoritos" to { showFavorites() }
            }
            "Ayuda" -> items += "Acerca de MPM Player" to {
                AlertDialog.Builder(this).setTitle("MPM Player")
                    .setMessage("Reproductor multimedia para Android.\\n\\nUsa Archivo para abrir videos y subtítulos, Ver para ajustar la pantalla, y Reproducir o Navegar para controlar la reproducción.")
                    .setPositiveButton("Aceptar", null).show()
            }
        }
        showMpmMenu(anchor, section.uppercase(), items)
    }

    private fun showMpmMenu(anchor: View, heading: String, items: List<Pair<String, () -> Unit>>) {
        if (items.isEmpty()) return
        val dialog = android.app.Dialog(this)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF151B24.toInt())
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), 0xFF344153.toInt())
            }
        }
        panel.addView(TextView(this).apply {
            text = heading
            setTextColor(0xFFFF8738.toInt())
            textSize = 11f
            letterSpacing = 0.12f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(dp(10), dp(8), dp(10), dp(12))
        })
        val scroll = android.widget.ScrollView(this).apply { isFillViewport = false }
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        items.forEachIndexed { index, item ->
            val row = TextView(this).apply {
                text = item.first
                setTextColor(0xFFE8EDF4.toInt())
                textSize = 14f
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(11), dp(12), dp(11))
                minHeight = dp(44)
                isClickable = true
                isFocusable = true
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(0xFF1D2632.toInt())
                    cornerRadius = dp(10).toFloat()
                }
                setOnClickListener {
                    dialog.dismiss()
                    item.second.invoke()
                }
            }
            rows.addView(row, LinearLayout.LayoutParams(-1, -2).apply {
                if (index > 0) topMargin = dp(5)
            })
        }
        scroll.addView(rows)
        panel.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        dialog.setContentView(panel)
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0x00000000))
        dialog.show()
        val maxHeight = (resources.displayMetrics.heightPixels * 0.78f).toInt()
        dialog.window?.setLayout(minOf(dp(320), resources.displayMetrics.widthPixels - dp(28)), maxHeight)
        dialog.window?.setGravity(android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL)
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        dialog.window?.attributes = dialog.window?.attributes?.apply { dimAmount = 0.48f }
    }

    private fun getPlayerTitle(uri: Uri): String {
        val fullName = getDisplayName(uri)
        val name = fullName.substringBeforeLast('.', fullName).trim()
        if (name.length <= 48) return name
        return name.take(22) + "…" + name.takeLast(22)
    }

    private fun setControlsVisible(visible: Boolean) {
        val shouldShow = visible && !isLibraryVisible
        isControlsVisible = shouldShow
        val state = if (shouldShow) View.VISIBLE else View.GONE
        topBar.visibility = state
        bottomBar.visibility = state
        // VLC mantiene el control principal en la fila inferior, no como botón flotante duplicado.
        centerControls.visibility = View.GONE
        btnScreenshotFloat.visibility = if (shouldShow && currentUri != null &&
            getSharedPreferences("mpm_player", MODE_PRIVATE).getBoolean("floating_screenshot", true)) View.VISIBLE else View.GONE
        handler.removeCallbacks(controlsHideRunnable)
        if (shouldShow && mediaPlayer?.isPlaying == true) handler.postDelayed(controlsHideRunnable, 3500L)
    }

    private fun applyResponsiveControls() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val bottom = bottomBar as? LinearLayout ?: return

        // Compact VLC-style HUD: time, seekbar and transport controls stay close together.
        bottom.setPadding(dp(if (landscape) 12 else 10), dp(if (landscape) 0 else 1),
            dp(if (landscape) 12 else 10), dp(if (landscape) 0 else 1))
        bottom.getChildAt(0)?.let { row ->
            row.layoutParams = row.layoutParams.apply { height = dp(if (landscape) 13 else 18) }
        }
        seekBar.layoutParams = seekBar.layoutParams.apply { height = dp(if (landscape) 11 else 18) }
        bottom.findViewById<View>(R.id.playerControlsRow)?.let { row ->
            row.layoutParams = row.layoutParams.apply { height = dp(if (landscape) 30 else 40) }
        }

        val compactGroups = if (landscape) listOf(
            R.id.playerControlsLeft to intArrayOf(32, 32),
            R.id.playerControlsCenter to intArrayOf(34, 38, 34),
            R.id.playerControlsRight to intArrayOf(30, 30, 36, 30)
        ) else listOf(
            R.id.playerControlsLeft to intArrayOf(34, 34),
            R.id.playerControlsCenter to intArrayOf(38, 46, 38),
            R.id.playerControlsRight to intArrayOf(32, 32, 40, 32)
        )
        compactGroups.forEach { (groupId, widths) ->
            bottom.findViewById<LinearLayout>(groupId)?.let { group ->
                widths.forEachIndexed { index, width ->
                    group.getChildAt(index)?.let { child ->
                        child.layoutParams = child.layoutParams.apply {
                            this.width = dp(width)
                            if (landscape) this.height = dp(30)
                        }
                    }
                }
            }
        }
        topBar.layoutParams = topBar.layoutParams.apply { height = dp(if (landscape) 38 else 46) }
    }

    private fun togglePlayback() {
        val player = mediaPlayer ?: return
        if (player.isPlaying) player.pause() else player.play()
    }

    private fun seekBy(seconds: Int) {
        val player = mediaPlayer ?: return
        val target = (player.time + seconds * 1000L).coerceAtLeast(0L)
        player.time = if (player.length > 0L) target.coerceAtMost(player.length) else target
        showOsd((if (seconds < 0) "−" else "+") + kotlin.math.abs(seconds) + " s · " + formatTime(player.time))
    }

    private fun initViews() {
        videoLayout = findViewById(R.id.videoLayout)
        welcomeScreen = findViewById(R.id.welcomeScreen)
        libraryItems = findViewById(R.id.libraryItems)
        libraryHeading = findViewById(R.id.libraryHeading)
        librarySubtitle = findViewById(R.id.librarySubtitle)
        topBar = findViewById(R.id.topBar)
        findViewById<ImageButton>(R.id.btnPlayerBack).setOnClickListener {
            savePlaybackProgress()
            mediaPlayer?.pause()
            welcomeScreen.visibility = View.VISIBLE
            setControlsVisible(false)
            renderLibrary(libraryMode)
            showSystemBars()
        }
        bottomBar = findViewById(R.id.bottomBar)
        centerControls = findViewById(R.id.centerControls)
        btnCenterPlay = findViewById(R.id.btnCenterPlay)
        txtStatus = findViewById(R.id.txtStatus)
        txtDecoderInfo = findViewById(R.id.txtDecoderInfo)
        txtTime = findViewById(R.id.txtTime)
        txtRemaining = findViewById(R.id.txtRemaining)
        btnScreenshotFloat = findViewById(R.id.btnScreenshotFloat)
        topBar.visibility = View.GONE
        bottomBar.visibility = View.GONE
        centerControls.visibility = View.GONE
        btnScreenshotFloat.visibility = View.GONE
        seekIntervalSeconds = getSharedPreferences("mpm_player", MODE_PRIVATE).getInt("seek_interval_seconds", 5)
        playbackMode = getSharedPreferences("mpm_player", MODE_PRIVATE).getInt("playback_mode", 0).coerceIn(0, 3)
        txtOsd = findViewById(R.id.txtOsd)
        seekBar = findViewById(R.id.seekBar)
        btnPlay = findViewById(R.id.btnPlay)
        btnHwSw = findViewById(R.id.btnHwSw)
        btnSpeed = findViewById(R.id.btnSpeed)

        findViewById<ImageButton>(R.id.btnAudioTrack).setOnClickListener { showAudioSubsPanel("Audio") }
        findViewById<ImageButton>(R.id.btnCastBottom).setOnClickListener { showCastDialog() }
        findViewById<ImageButton>(R.id.btnSubtitleTrack).setOnClickListener { showAudioSubsPanel("Subs") }

        findViewById<Button>(R.id.btnMainMenu).setOnClickListener { showMainMenu(it) }

        findViewById<Button>(R.id.btnLibraryOpen).setOnClickListener { pickFile() }
        findViewById<Button>(R.id.btnLibraryRefresh).setOnClickListener { refreshLibrary() }
        findViewById<Button>(R.id.btnLibrarySettings).setOnClickListener { showPlayerSettings() }
        findViewById<Button>(R.id.btnLibraryVideos).setOnClickListener { renderLibrary("Videos") }
        findViewById<Button>(R.id.btnLibraryFolders).setOnClickListener { renderLibrary("Carpetas") }
        findViewById<Button>(R.id.btnLibraryRecent).setOnClickListener { renderLibrary("Recientes") }
        findViewById<Button>(R.id.btnLibraryFavorites).setOnClickListener { renderLibrary("Favoritos") }
        findViewById<Button>(R.id.btnLibraryPlaylist).setOnClickListener { showPlaylist() }
        findViewById<EditText>(R.id.librarySearch).addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) {
                librarySearchQuery = text?.toString()?.trim().orEmpty()
                renderLibrary(libraryMode)
            }
            override fun afterTextChanged(editable: android.text.Editable?) = Unit
        })
        btnScreenshotFloat.setOnClickListener { takeScreenshot() }
        btnScreenshotFloat.visibility = if (getSharedPreferences("mpm_player", MODE_PRIVATE).getBoolean("floating_screenshot", true) && isControlsVisible) View.VISIBLE else View.GONE
        findViewById<ImageButton>(R.id.btnRotateBottom).setOnClickListener { cycleRotation() }

        btnPlay.setOnClickListener { togglePlayback() }
        btnCenterPlay.setOnClickListener { togglePlayback() }
        findViewById<Button>(R.id.btnPause).setOnClickListener { mediaPlayer?.pause() }

        findViewById<Button>(R.id.btnStop).setOnClickListener {
            mediaPlayer?.stop()
            releaseCurrentAssetDescriptor()
            welcomeScreen.visibility = View.VISIBLE
            renderLibrary(libraryMode)
            txtStatus.text = "Detenido"
            txtTime.text = "00:00:00"
            txtRemaining.text = "-00:00:00"
            seekBar.progress = 0
        }

        findViewById<Button>(R.id.btnBack).setOnClickListener { seekBy(-seekIntervalSeconds) }

        findViewById<Button>(R.id.btnForward).setOnClickListener { seekBy(seekIntervalSeconds) }

        btnSpeed.setOnClickListener {
            speedIndex = (speedIndex + 1) % speeds.size
            val spd = speeds[speedIndex]
            mediaPlayer?.rate = spd
            btnSpeed.text = "${spd}X"
            showOsd("Velocidad: ${spd}X")
        }

        btnHwSw.setOnClickListener {
            isHw = !isHw
            updateDecoderUi()
            showOsd(if (isHw) "Decodificador Hardware (HW)" else "Decodificador Software (SW)")
            currentUri?.let { u ->
                val pos = mediaPlayer?.time ?: 0L
                rebuildPlayer()
                play(u, pos)
            }
        }

        applyResponsiveControls()
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && mediaPlayer != null) {
                    val len = mediaPlayer!!.length
                    if (len > 0) {
                        val t = (len * (progress / 1000f)).toLong()
                        txtTime.text = formatTime(t)
                        txtRemaining.text = "-${formatTime((len - t).coerceAtLeast(0L))}"
                    }
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { isSeeking = true }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                isSeeking = false
                mediaPlayer?.let { p ->
                    if (p.length > 0) p.time = (p.length * (seekBar.progress / 1000f)).toLong()
                }
            }
        })
    }

    private fun ensureLibraryPermission() {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) refreshLibrary()
        else ActivityCompat.requestPermissions(this, arrayOf(permission), 9042)
    }
    private fun showLibraryEmpty(message: String) {
        libraryItems.removeAllViews()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setPadding(dp(24), dp(38), dp(24), dp(38))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF151B24.toInt())
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), 0xFF263140.toInt())
            }
        }
        panel.addView(TextView(this).apply {
            text = "▶"
            gravity = android.view.Gravity.CENTER
            textSize = 30f
            setTextColor(0xFFFF8738.toInt())
        }, LinearLayout.LayoutParams(-1, dp(48)))
        panel.addView(TextView(this).apply {
            text = message
            gravity = android.view.Gravity.CENTER
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(0xFFF1F4F8.toInt())
            setPadding(0, dp(10), 0, dp(5))
        }, LinearLayout.LayoutParams(-1, -2))
        panel.addView(TextView(this).apply {
            text = "Abre un archivo para empezar a reproducir"
            gravity = android.view.Gravity.CENTER
            textSize = 12f
            setTextColor(0xFF929CAB.toInt())
        }, LinearLayout.LayoutParams(-1, -2))
        libraryItems.addView(panel, LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(0, dp(8), 0, dp(8))
        })
    }
    private fun refreshLibrary() {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) { ensureLibraryPermission(); return }
        val found = mutableListOf<LibraryVideo>()
        val base = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val cols = mutableListOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.DURATION)
        if (Build.VERSION.SDK_INT >= 29) cols.add(MediaStore.Video.Media.RELATIVE_PATH)
        try {
            contentResolver.query(base, cols.toTypedArray(), null, null, MediaStore.Video.Media.DATE_ADDED + " DESC")?.use { cur ->
                val id = cur.getColumnIndexOrThrow(MediaStore.Video.Media._ID); val name = cur.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME); val duration = cur.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val folder = if (Build.VERSION.SDK_INT >= 29) cur.getColumnIndex(MediaStore.Video.Media.RELATIVE_PATH) else -1
                while (cur.moveToNext()) {
                    val path = if (folder >= 0) cur.getString(folder).orEmpty().trimEnd('/') else "Almacenamiento interno"
                    found.add(LibraryVideo(android.content.ContentUris.withAppendedId(base, cur.getLong(id)), cur.getString(name) ?: "Vídeo", cur.getLong(duration), path.ifBlank { "Almacenamiento interno" }))
                }
            }
        } catch (e: Exception) { Log.e("MPM_PLAYER", "Error al leer biblioteca", e) }
        libraryVideos.clear(); libraryVideos.addAll(found); renderLibrary(libraryMode)
    }
    private fun renderLibrary(mode: String) {
        isLibraryVisible = true
        handler.removeCallbacks(controlsHideRunnable)
        // Keep the paused LibVLC session alive, but remove its surface and touch target
        // completely while the library is the active screen.
        videoLayout.visibility = View.GONE
        libraryMode = mode; libraryItems.removeAllViews()
        topBar.visibility = View.GONE
        bottomBar.visibility = View.GONE
        centerControls.visibility = View.GONE
        btnScreenshotFloat.visibility = View.GONE
        val prefs = getSharedPreferences("mpm_player", MODE_PRIVATE)
        val recent = prefs.getString("recent_files_ordered", "")?.split("\\n")?.filter { it.isNotBlank() }.orEmpty()
        val favorites = prefs.getStringSet("favorites", emptySet()).orEmpty()
        val chosenBase = when (mode) {
            "Recientes" -> { val map = libraryVideos.associateBy { it.uri.toString() }; recent.mapNotNull { map[it] } }
            "Favoritos" -> libraryVideos.filter { favorites.contains(it.uri.toString()) }
            else -> libraryVideos
        }
        val query = librarySearchQuery.lowercase(Locale.ROOT)
        val chosen = if (query.isBlank()) chosenBase else chosenBase.filter {
            it.name.lowercase(Locale.ROOT).contains(query) || it.folder.lowercase(Locale.ROOT).contains(query)
        }
        libraryHeading.text = when (mode) {
            "Carpetas" -> if (selectedFolder.isBlank()) "Carpetas" else selectedFolder.substringAfterLast('/')
            "Recientes" -> "Vistos recientemente"
            "Favoritos" -> "Tus favoritos"
            else -> "Tu biblioteca"
        }
        librarySubtitle.text = when (mode) {
            "Carpetas" -> "Explora tus vídeos por ubicación"
            "Recientes" -> "${chosen.size} vídeos reproducidos"
            "Favoritos" -> "${chosen.size} vídeos guardados"
            else -> "${libraryVideos.size} vídeos en tu dispositivo"
        }
        val ids = listOf(R.id.btnLibraryVideos, R.id.btnLibraryFolders, R.id.btnLibraryRecent, R.id.btnLibraryFavorites)
        val active = when (mode) { "Carpetas" -> R.id.btnLibraryFolders; "Recientes" -> R.id.btnLibraryRecent; "Favoritos" -> R.id.btnLibraryFavorites; else -> R.id.btnLibraryVideos }
        ids.forEach { id ->
            val selected = id == active
            findViewById<Button>(id).apply {
                setTextColor(if (selected) 0xFFFF8738.toInt() else 0xFFAAB3C0.toInt())
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(if (selected) 0xFF30251F.toInt() else 0x00000000)
                    cornerRadius = dp(18).toFloat()
                    if (selected) setStroke(dp(1), 0xFF62402D.toInt())
                }
            }
        }
        if (mode == "Carpetas") {
            if (selectedFolder.isNotBlank()) {
                libraryItems.addView(TextView(this).apply {
                    text = "‹   Todas las carpetas"
                    textSize = 13f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(0xFFFF8738.toInt())
                    setPadding(dp(8), dp(10), 0, dp(15))
                    setOnClickListener { selectedFolder = ""; renderLibrary("Carpetas") }
                })
                libraryVideos.filter { it.folder == selectedFolder }.forEach { addLibraryVideoRow(it) }
            } else {
                val folders = libraryVideos.map { it.folder }.distinct().filter { query.isBlank() || it.lowercase(Locale.ROOT).contains(query) }.sorted()
                if (folders.isEmpty()) showLibraryEmpty("No se encontraron carpetas con vídeos")
                folders.forEach { folder ->
                    val count = libraryVideos.count { it.folder == folder }
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        setPadding(dp(16), dp(14), dp(16), dp(14))
                        background = android.graphics.drawable.GradientDrawable().apply {
                            setColor(0xFF151B24.toInt()); cornerRadius = dp(14).toFloat()
                            setStroke(dp(1), 0xFF263140.toInt())
                        }
                        isClickable = true; isFocusable = true
                        setOnClickListener { selectedFolder = folder; renderLibrary("Carpetas") }
                    }
                    row.addView(TextView(this).apply {
                        text = "▰"; textSize = 22f; setTextColor(0xFFFF8738.toInt())
                        gravity = android.view.Gravity.CENTER
                    }, LinearLayout.LayoutParams(dp(38), dp(42)))
                    val details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0) }
                    details.addView(TextView(this).apply {
                        text = folder.substringAfterLast('/'); textSize = 14f
                        setTypeface(null, android.graphics.Typeface.BOLD); setTextColor(0xFFF2F4F7.toInt())
                    })
                    details.addView(TextView(this).apply {
                        text = "$count vídeos"; textSize = 11f; setTextColor(0xFF929CAB.toInt())
                        setPadding(0, dp(4), 0, 0)
                    })
                    row.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
                    row.addView(TextView(this).apply { text = "›"; textSize = 24f; setTextColor(0xFF7F8998.toInt()) })
                    libraryItems.addView(row, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(5), 0, dp(5)) })
                }
            }
            return
        }
        if (chosen.isEmpty()) {
            showLibraryEmpty(when (mode) {
                "Favoritos" -> "Todavía no tienes favoritos"
                "Recientes" -> "Aún no has reproducido vídeos"
                else -> if (query.isNotBlank()) "No hay resultados para tu búsqueda" else "No se encontraron vídeos en el dispositivo"
            })
            return
        }
        chosen.forEach { addLibraryVideoRow(it) }
    }
    private fun addLibraryVideoRow(video: LibraryVideo) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(9), dp(9), dp(12), dp(9))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF151B24.toInt())
                cornerRadius = dp(15).toFloat()
                setStroke(dp(1), 0xFF263140.toInt())
            }
            isClickable = true
            isFocusable = true
        }
        val thumbFrame = android.widget.FrameLayout(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF242D39.toInt()); cornerRadius = dp(10).toFloat()
            }
            clipToOutline = true
        }
        val thumb = ImageView(this).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(-1, -1)
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0xFF242D39.toInt())
            setImageResource(android.R.drawable.ic_media_play)
        }
        thumbFrame.addView(thumb)
        thumbFrame.addView(TextView(this).apply {
            text = "▶"
            gravity = android.view.Gravity.CENTER
            textSize = 12f
            setTextColor(0xFFFFFFFF.toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0x990B0E13.toInt()); shape = android.graphics.drawable.GradientDrawable.OVAL
            }
        }, android.widget.FrameLayout.LayoutParams(dp(28), dp(28), android.view.Gravity.CENTER))
        row.addView(thumbFrame, LinearLayout.LayoutParams(dp(126), dp(78)))
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(13), 0, dp(4), 0)
        }
        info.addView(TextView(this).apply {
            text = video.name
            textSize = 13.5f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(0xFFF3F5F8.toInt())
        })
        info.addView(TextView(this).apply {
            text = formatTime(video.duration) + "   ·   " + video.folder.substringAfterLast('/')
            textSize = 10.5f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(0xFF929CAB.toInt())
            setPadding(0, dp(6), 0, 0)
        })
        row.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
        row.setOnClickListener { playlist.clear(); playlist.add(video.uri); playlistIndex = 0; play(video.uri) }
        row.setOnLongClickListener {
            AlertDialog.Builder(this).setTitle(video.name).setItems(arrayOf("Reproducir", "Añadir a favoritos", "Información")) { _, which ->
                when (which) {
                    0 -> { playlist.clear(); playlist.add(video.uri); playlistIndex = 0; play(video.uri) }
                    1 -> {
                        val set = getSharedPreferences("mpm_player", MODE_PRIVATE).getStringSet("favorites", emptySet())?.toMutableSet() ?: mutableSetOf()
                        set.add(video.uri.toString())
                        getSharedPreferences("mpm_player", MODE_PRIVATE).edit().putStringSet("favorites", set).apply()
                        renderLibrary(libraryMode)
                    }
                    2 -> { currentUri = video.uri; showMediaInfo() }
                }
            }.show()
            true
        }
        libraryItems.addView(row, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(5), 0, dp(5)) })
        if (Build.VERSION.SDK_INT >= 29) thumbnailExecutor.execute {
            try {
                val bmp = contentResolver.loadThumbnail(video.uri, android.util.Size(dp(126), dp(78)), null)
                runOnUiThread { if (!isDestroyed && thumb.parent != null) thumb.setImageBitmap(bmp) }
            } catch (e: Exception) { Log.d("MPM_PLAYER", "Miniatura no disponible: ${video.name}") }
        }
    }

    private fun initPlayer() {
        val opts = ArrayList<String>()
        opts.add("--file-caching=2000")
        opts.add("--network-caching=2000")
        opts.add("--avcodec-threads=4")
        opts.add("--avcodec-fast")
        // Dejar que VLC seleccione el decodificador de hardware compatible.
        // En modo software se desactiva explícitamente la aceleración.
        if (!isHw) {
            opts.add("--avcodec-hw=none")
        }

        libVLC = LibVLC(this, opts)
        mediaPlayer = MediaPlayer(libVLC)

        mediaPlayer?.setEventListener { ev ->
            val eventName = when (ev.type) {
                MediaPlayer.Event.Opening -> "Opening"
                MediaPlayer.Event.Buffering -> "Buffering"
                MediaPlayer.Event.Playing -> "Playing"
                MediaPlayer.Event.Paused -> "Paused"
                MediaPlayer.Event.Stopped -> "Stopped"
                MediaPlayer.Event.EndReached -> "EndReached"
                MediaPlayer.Event.EncounteredError -> "EncounteredError"
                else -> "Event-${ev.type}"
            }
            playbackEvents.add(eventName)
            if (playbackEvents.size > 20) playbackEvents.removeAt(0)
            Log.d("MPM_PLAYER", "VLC event: $eventName")
            when (ev.type) {
                MediaPlayer.Event.Playing -> {
                    isLibraryVisible = false
                    welcomeScreen.visibility = View.GONE
                    txtStatus.text = currentUri?.let { getPlayerTitle(it) } ?: "Reproduciendo"
                    btnPlay.setImageResource(R.drawable.vlc_ic_pause_player)
                    btnPlay.contentDescription = "Pausar"
                    btnCenterPlay.setImageResource(R.drawable.vlc_ic_play_player)
                    setControlsVisible(true)
                }
                MediaPlayer.Event.Paused -> {
                    if (isLibraryVisible) {
                        setControlsVisible(false)
                    } else {
                    txtStatus.text = currentUri?.let { getPlayerTitle(it) } ?: "Pausa"
                    btnPlay.setImageResource(R.drawable.vlc_ic_play_player)
                    btnPlay.contentDescription = "Reproducir"
                    btnCenterPlay.setImageResource(R.drawable.vlc_ic_play_player)
                    setControlsVisible(true)
                    }
                }
                MediaPlayer.Event.Stopped -> {
                    isLibraryVisible = true
                    welcomeScreen.visibility = View.VISIBLE
                    setControlsVisible(false)
                    txtStatus.text = "Detenido"
                    btnPlay.setImageResource(R.drawable.vlc_ic_play_player)
                    btnCenterPlay.setImageResource(R.drawable.vlc_ic_play_player)
                }
                MediaPlayer.Event.TimeChanged -> {
                    val t = ev.timeChanged
                    val l = mediaPlayer?.length ?: 0L
                    if (l > 0) {
                        if (!isSeeking) {
                            seekBar.progress = ((t.toFloat() / l) * 1000).toInt()
                            txtTime.text = formatTime(t)
                            txtRemaining.text = "-${formatTime((l - t).coerceAtLeast(0L))}"
                        }
                        val a = repeatA
                        val b = repeatB
                        if (a != null && b != null && b > a && t >= b) mediaPlayer?.time = a
                        if (t - lastProgressSaveAt >= 5000L) {
                            lastProgressSaveAt = t
                            savePlaybackProgress()
                        }
                    }
                }
                MediaPlayer.Event.EndReached -> {
                    currentUri?.let { clearPlaybackProgress(it) }
                    val currentIndex = playlistIndex
                    val nextIndex = when {
                        playlist.isEmpty() || currentIndex !in playlist.indices -> -1
                        playbackMode == 2 -> currentIndex
                        playbackMode == 3 && playlist.size > 1 -> {
                            val candidates = playlist.indices.filter { it != currentIndex }
                            candidates.random()
                        }
                        currentIndex < playlist.lastIndex -> currentIndex + 1
                        playbackMode == 1 -> 0
                        else -> -1
                    }
                    if (nextIndex >= 0) {
                        playlistIndex = nextIndex
                        val next = playlist[nextIndex]
                        handler.post {
                            play(next, 0L)
                            showOsd(if (playbackMode == 2) "Repitiendo vídeo" else "Siguiente: " + getDisplayName(next))
                        }
                    } else handler.post { txtStatus.text = "Lista finalizada" }
                }
                MediaPlayer.Event.EncounteredError -> {
                    txtStatus.text = "Error al abrir"
                    val report = playbackDiagnostics + "\n\nEventos VLC: " + playbackEvents.joinToString(" → ")
                    Log.e("MPM_PLAYER", report)
                    AlertDialog.Builder(this)
                        .setTitle("Diagnóstico de reproducción")
                        .setMessage(report)
                        .setPositiveButton("Cerrar", null)
                        .show()
                }
            }
        }
        updateDecoderUi()
    }

    private fun rebuildPlayer() {
        stopRendererDiscovery()
        activeRendererName = null
        mediaPlayer?.setRenderer(null)
        mediaPlayer?.stop()
        releaseCurrentAssetDescriptor()
        if (viewsAttached) {
            mediaPlayer?.detachViews()
            viewsAttached = false
        }
        mediaPlayer?.release()
        libVLC?.release()
        initPlayer()
    }

    private fun play(uri: Uri, startPos: Long = -1L) {
        isLibraryVisible = false
        videoLayout.visibility = View.VISIBLE
        if (startPos < 0L) {
            val saved = getPlaybackProgress(uri)
            if (saved > 10_000L) {
                val name = getDisplayName(uri)
                AlertDialog.Builder(this)
                    .setTitle("Continuar reproducción")
                    .setMessage("¿Quieres continuar ${name} desde ${formatTime(saved)} o empezar desde el principio?")
                    .setPositiveButton("Continuar") { _, _ -> play(uri, saved) }
                    .setNegativeButton("Empezar de cero") { _, _ ->
                        clearPlaybackProgress(uri)
                        play(uri, 0L)
                    }
                    .setNeutralButton("Cancelar", null)
                    .show()
                return
            }
            play(uri, 0L)
            return
        }
        if (!viewsAttached) {
            videoLayout.post {
                if (isFinishing || isDestroyed) return@post
                if (!viewsAttached) {
                    mediaPlayer?.attachViews(videoLayout, null, false, true)
                    viewsAttached = true
                }
                play(uri, startPos)
            }
            return
        }

        val requestId = playbackRequestId.incrementAndGet()
        mediaPlayer?.stop()
        releaseCurrentAssetDescriptor()
        currentUri = uri
        if (playlistIndex < 0 || playlist.getOrNull(playlistIndex) != uri) {
            playlist.clear()
            playlist.add(uri)
            playlistIndex = 0
        }
        rememberRecent(uri)
        lastProgressSaveAt = 0L
        playbackEvents.clear()
        txtStatus.text = "Preparando vídeo…"
        welcomeScreen.visibility = View.GONE

        // SAF providers and media probing can block, so prepare media off the UI thread.
        playbackExecutor.execute {
            var afd: AssetFileDescriptor? = null
            var media: Media? = null
            try {
                var displayName = "desconocido"
                var fileSize = "desconocido"
                val mimeType = contentResolver.getType(uri) ?: "desconocido"
                try {
                    contentResolver.query(uri, null, null, null, null)?.use { c ->
                        if (c.moveToFirst()) {
                            val nameIndex = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            val sizeIndex = c.getColumnIndex(OpenableColumns.SIZE)
                            if (nameIndex >= 0) displayName = c.getString(nameIndex) ?: displayName
                            if (sizeIndex >= 0) fileSize = c.getString(sizeIndex) ?: fileSize
                        }
                    }
                } catch (queryError: Exception) {
                    Log.w("MPM_PLAYER", "No se pudieron consultar los metadatos del archivo", queryError)
                }
                val activeVlc = libVLC ?: throw IllegalStateException("El motor VLC no está disponible")
                val openedAfd = contentResolver.openAssetFileDescriptor(uri, "r")
                    ?: throw IllegalStateException("Android no pudo abrir el descriptor del archivo")
                afd = openedAfd
                val preparedMedia = Media(activeVlc, openedAfd)
                media = preparedMedia
                preparedMedia.addOption(":file-caching=2000")

                val preparedAfd = openedAfd
                val diagnostics = "Archivo: $displayName\nTamaño: $fileSize bytes\nTipo: $mimeType\nURI: $uri\nEsquema: ${uri.scheme}\nHW/SW: ${if (isHw) "HW" else "SW"}"
                runOnUiThread {
                    if (isFinishing || isDestroyed || requestId != playbackRequestId.get()) {
                        preparedMedia.release()
                        try { preparedAfd.close() } catch (_: Exception) {}
                        return@runOnUiThread
                    }
                    try {
                        playbackDiagnostics = diagnostics
                        Log.d("MPM_PLAYER", diagnostics)
                        currentAssetDescriptor = preparedAfd
                        mediaPlayer?.media = preparedMedia
                        preparedMedia.release()
                        mediaPlayer?.play()
                        if (startPos > 0L) {
                            handler.postDelayed({
                                if (requestId == playbackRequestId.get()) mediaPlayer?.time = startPos
                            }, 500L)
                        }
                    } catch (playError: Exception) {
                        try { preparedMedia.release() } catch (_: Exception) {}
                        releaseCurrentAssetDescriptor()
                        playbackDiagnostics = "Excepción: ${playError.javaClass.name}\nMensaje: ${playError.message}\nURI: $uri"
                        Log.e("MPM_PLAYER", playbackDiagnostics, playError)
                        txtStatus.text = "No se pudo abrir el archivo"
                        AlertDialog.Builder(this).setTitle("Error al abrir archivo")
                            .setMessage(playbackDiagnostics).setPositiveButton("Cerrar", null).show()
                    }
                }
                afd = null
                media = null
            } catch (e: Exception) {
                try { media?.release() } catch (_: Exception) {}
                try { afd?.close() } catch (_: Exception) {}
                runOnUiThread {
                    if (!isFinishing && !isDestroyed && requestId == playbackRequestId.get()) {
                        playbackDiagnostics = "Excepción: ${e.javaClass.name}\nMensaje: ${e.message}\nURI: $uri"
                        Log.e("MPM_PLAYER", playbackDiagnostics, e)
                        txtStatus.text = "No se pudo abrir el archivo"
                        AlertDialog.Builder(this).setTitle("Error al abrir archivo")
                            .setMessage(playbackDiagnostics).setPositiveButton("Cerrar", null).show()
                    }
                }
            }
        }
    }

    private fun progressKey(uri: Uri): String = "progress_" + uri.toString().hashCode().toString()

    private fun getPlaybackProgress(uri: Uri): Long =
        getSharedPreferences("mpm_player", MODE_PRIVATE).getLong(progressKey(uri), 0L)

    private fun savePlaybackProgress() {
        val uri = currentUri ?: return
        val player = mediaPlayer ?: return
        val position = player.time
        val duration = player.length
        if (position > 0L && duration > 0L && position < duration - 10_000L) {
            getSharedPreferences("mpm_player", MODE_PRIVATE).edit()
                .putLong(progressKey(uri), position).apply()
        }
    }

    private fun clearPlaybackProgress(uri: Uri) {
        getSharedPreferences("mpm_player", MODE_PRIVATE).edit()
            .remove(progressKey(uri)).apply()
    }

    private fun releaseCurrentAssetDescriptor() {
        try {
            currentAssetDescriptor?.close()
        } catch (e: Exception) {
            Log.w("MPM_PLAYER", "No se pudo cerrar el descriptor anterior", e)
        } finally {
            currentAssetDescriptor = null
        }
    }

    private fun pickFile() {
        filePickerPending = true
        resumeAfterFilePicker = mediaPlayer?.isPlaying == true
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                      type = "video/*"
        }
        try {
            fileLauncher.launch(intent)
        } catch (e: Exception) {
            filePickerPending = false
            resumeAfterFilePicker = false
            Toast.makeText(this, "No se pudo abrir el selector de archivos", Toast.LENGTH_LONG).show()
        }
    }

    private fun persistReadPermission(uri: Uri, flags: Int) {
        val granted = flags and Intent.FLAG_GRANT_READ_URI_PERMISSION
        if (granted != 0) {
            try {
                contentResolver.takePersistableUriPermission(uri, granted)
            } catch (e: Exception) {
                Log.w("MPM_PLAYER", "El proveedor no permite conservar el permiso del archivo", e)
            }
        }
    }

    private fun getDisplayName(uri: Uri): String {
        var name = uri.lastPathSegment ?: "Video"
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val index = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) name = c.getString(index) ?: name
                }
            }
        } catch (_: Exception) { }
        return name
    }

    private fun saveFavorite() {
        val uri = currentUri
        if (uri == null) {
            Toast.makeText(this, "Primero abre un video", Toast.LENGTH_SHORT).show()
            return
        }
        val favorites = getSharedPreferences("mpm_player", MODE_PRIVATE)
            .getStringSet("favorites", emptySet())?.toMutableSet() ?: mutableSetOf()
        favorites.add(uri.toString())
        getSharedPreferences("mpm_player", MODE_PRIVATE).edit()
            .putStringSet("favorites", favorites).apply()
        showOsd("Añadido a favoritos")
    }

    private fun showFavorites() {
        val favorites = getSharedPreferences("mpm_player", MODE_PRIVATE)
            .getStringSet("favorites", emptySet())?.toList().orEmpty()
        if (favorites.isEmpty()) {
            AlertDialog.Builder(this).setTitle("Favoritos")
                .setMessage("Todavía no has guardado videos")
                .setPositiveButton("Aceptar", null).show()
            return
        }
        var selected = 0
        val adapter = object : BaseAdapter() {
            override fun getCount(): Int = favorites.size
            override fun getItem(position: Int): Any = favorites[position]
            override fun getItemId(position: Int): Long = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup?): View {
                val row = convertView as? LinearLayout ?: LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(dp(8), dp(6), dp(8), dp(6))
                    val image = ImageView(this@MainActivity).apply {
                        tag = "favorite_thumbnail"
                        layoutParams = LinearLayout.LayoutParams(dp(76), dp(58))
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        setBackgroundColor(0xFF252525.toInt())
                    }
                    val label = TextView(this@MainActivity).apply {
                        tag = "favorite_label"
                        setTextColor(0xFFFFFFFF.toInt())
                        textSize = 14f
                        maxLines = 2
                        setPadding(dp(12), 0, 0, 0)
                    }
                    addView(image)
                    addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                }
                val image = row.findViewWithTag<ImageView>("favorite_thumbnail")
                val label = row.findViewWithTag<TextView>("favorite_label")
                val uri = Uri.parse(favorites[position])
                label.text = getDisplayName(uri)
                image.setImageDrawable(null)
                image.setImageResource(android.R.drawable.ic_menu_report_image)
                val expectedTag = "favorite_thumbnail:" + uri.toString()
                image.tag = "favorite_thumbnail"
                image.contentDescription = expectedTag
                val cacheFile = File(cacheDir, "favorite_" + uri.toString().hashCode() + ".jpg")
                if (cacheFile.exists() && cacheFile.length() > 0L) {
                    val target = image
                    thumbnailExecutor.execute {
                        val cachedBitmap = BitmapFactory.decodeFile(cacheFile.absolutePath)
                        runOnUiThread {
                            if (target.contentDescription == expectedTag && cachedBitmap != null) {
                                target.setImageBitmap(cachedBitmap)
                            }
                        }
                    }
                } else {
                    val target = image
                    thumbnailExecutor.execute {
                        var bitmap: Bitmap? = null
                        try {
                            val retriever = MediaMetadataRetriever()
                            try {
                                retriever.setDataSource(this@MainActivity, uri)
                                bitmap = retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                                    ?: retriever.getFrameAtTime(3_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                                    ?: retriever.getFrameAtTime(500_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                                    ?: retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                                    ?: retriever.getFrameAtTime(-1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                            } finally {
                                retriever.release()
                            }
                            bitmap?.let { frame ->
                                FileOutputStream(cacheFile).use { out ->
                                    frame.compress(Bitmap.CompressFormat.JPEG, 82, out)
                                }
                            }
                        } catch (e: Exception) {
                            Log.w("MPM_PLAYER", "No se pudo generar miniatura del favorito", e)
                        }
                        runOnUiThread {
                            if (target.contentDescription == expectedTag && bitmap != null) {
                                target.setImageBitmap(bitmap)
                            }
                        }
                    }
                }
                return row
            }
        }
        AlertDialog.Builder(this).setTitle("Videos favoritos")
            .setSingleChoiceItems(adapter, 0) { _, which -> selected = which.coerceIn(favorites.indices) }
            .setPositiveButton("Abrir") { _, _ ->
                favorites.getOrNull(selected)?.let { value ->
                    val uri = Uri.parse(value)
                    try {
                        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    } catch (_: Exception) { }
                    play(uri)
                }
            }
            .setNeutralButton("Quitar") { _, _ ->
                favorites.getOrNull(selected)?.let { value ->
                    val updated = favorites.toMutableSet()
                    updated.remove(value)
                    getSharedPreferences("mpm_player", MODE_PRIVATE).edit()
                        .putStringSet("favorites", updated).apply()
                    showOsd("Favorito eliminado")
                }
            }
            .setNegativeButton("Cerrar", null).show()
    }

    private fun pickExternalSubtitle() {
        if (currentUri == null) {
            Toast.makeText(this, "Primero abre un video", Toast.LENGTH_SHORT).show()
            return
        }
        subtitlePickerPending = true
        resumeAfterSubtitlePicker = mediaPlayer?.isPlaying == true
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            type = "*/*"
        }
        try {
            subtitleLauncher.launch(intent)
        } catch (e: Exception) {
            subtitlePickerPending = false
            Toast.makeText(this, "No se pudo abrir el selector de subtítulos", Toast.LENGTH_LONG).show()
        }
    }

    private fun finishSubtitlePicker() {
        subtitlePickerPending = false
        val shouldResume = resumeAfterSubtitlePicker
        resumeAfterSubtitlePicker = false
        videoLayout.postDelayed({ restoreVideoOutput(shouldResume) }, 250)
    }

    private fun restoreVideoOutput(shouldResume: Boolean) {
        val player = mediaPlayer ?: return
        if (currentUri == null) return
        try {
            // No desmontar la superficie al volver del selector: hacerlo provoca
            // un parpadeo negro y puede reiniciar la salida de video.
            if (!viewsAttached) {
                player.attachViews(videoLayout, null, false, true)
                viewsAttached = true
            }
            videoLayout.requestLayout()
            if (shouldResume && !player.isPlaying) player.play()
        } catch (e: Exception) {
            Log.w("MPM_PLAYER", "No se pudo restaurar la salida de video", e)
        }
    }

    private fun attachExternalSubtitle(uri: Uri) {
        Thread {
            try {
                val displayName = getDisplayName(uri)
                val extension = displayName.substringAfterLast('.', "srt")
                    .lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }.take(8).ifBlank { "srt" }
                val subtitleFile = java.io.File(cacheDir, "mpm_subtitle_${System.currentTimeMillis()}.$extension")
                contentResolver.openInputStream(uri)?.use { input ->
                    subtitleFile.outputStream().use { output -> input.copyTo(output) }
                } ?: throw IllegalStateException("No se pudo leer el subtítulo")
                runOnUiThread {
                    val added = mediaPlayer?.addSlave(0, Uri.fromFile(subtitleFile), true) ?: false // 0 = libvlc_media_slave_type_subtitle
                    if (added) showOsd("Subtítulo cargado: $displayName")
                    else Toast.makeText(this, "VLC no pudo cargar este subtítulo", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.e("MPM_PLAYER", "Error al cargar subtítulo externo", e)
                runOnUiThread {
                    AlertDialog.Builder(this).setTitle("Error de subtítulos")
                        .setMessage(e.message ?: "No se pudo cargar el archivo")
                        .setPositiveButton("Aceptar", null).show()
                }
            }
        }.start()
    }

    private fun cycleRotation() {
        rotationMode = (rotationMode + 1) % 3
        when (rotationMode) {
            0 -> {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
                showOsd("Pantalla: Sensor Automático")
            }
            1 -> {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                showOsd("Pantalla: Horizontal (Landscape)")
            }
            2 -> {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                showOsd("Pantalla: Vertical (Portrait)")
            }
        }
        enterImmersiveMode()
        videoLayout.post { videoLayout.requestLayout() }
    }

    private fun applyDisplayUnderNotch() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = if (displayUnderNotch) {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } else {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
                }
            }
        }
        if (immersiveMode) enterImmersiveMode() else showSystemBars()
        window.decorView.requestLayout()
    }

    // La biblioteca y la reproducción normal conservan las barras del sistema,
    // como VLC. Solo se ocultan cuando el usuario activa pantalla completa.
    private fun showSystemBars() {
        immersiveMode = false
        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = 0xFF101722.toInt()
        window.navigationBarColor = 0xFF101722.toInt()
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.show(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
            controller.isAppearanceLightStatusBars = false
            controller.isAppearanceLightNavigationBars = false
        }
    }

    private fun enterImmersiveMode() {
        immersiveMode = true
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun applyPreferredRefreshRate() {
        // No fijar la pantalla a su Hz máximo: LibVLC conserva la cadencia
        // original del video y Android puede gestionar el refresco disponible.
        // Un refresco de 90/120 Hz no convierte un video de 24 FPS en 90/120 FPS.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                window.attributes = window.attributes.apply {
                    preferredRefreshRate = 0f
                    preferredDisplayModeId = 0
                }
            } catch (e: Exception) {
                Log.w("MPM_PLAYER", "No se pudo dejar la frecuencia de pantalla en modo automático", e)
            }
        }
    }

    private fun displayRefreshRateLabel(): String {
        return try {
            // Mostrar el refresco actual, no el máximo que admite el dispositivo.
            windowManager.defaultDisplay.refreshRate.toInt().toString() + " Hz"
        } catch (_: Exception) {
            "Hz automático"
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            window.decorView.post {
                if (immersiveMode) enterImmersiveMode() else showSystemBars()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        window.decorView.post {
            if (immersiveMode) enterImmersiveMode() else showSystemBars()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        videoLayout.post {
            applyResponsiveControls()
            videoLayout.requestLayout()
            if (immersiveMode) enterImmersiveMode() else showSystemBars()
        }
    }

    private fun applyAspect(index: Int) {
        aspectIndex = index.coerceIn(aspects.indices)
        mediaPlayer?.aspectRatio = aspectValues[aspectIndex]
        showOsd("Aspecto: ${aspects[aspectIndex]}")
    }

    private fun cycleAspect() {
        applyAspect((aspectIndex + 1) % aspects.size)
    }

    private fun showAspectMenu() {
        AlertDialog.Builder(this)
            .setTitle("Relación de aspecto")
            .setSingleChoiceItems(aspects, aspectIndex) { dialog, which ->
                applyAspect(which)
                dialog.dismiss()
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun pickAudio() {
        val tracks = mediaPlayer?.audioTracks ?: return
        if (tracks.isEmpty()) return
        val names = tracks.map { it.name }.toTypedArray()
        val cur = mediaPlayer?.audioTrack ?: -1
        val sel = tracks.indexOfFirst { it.id == cur }.coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle("Audio (MPM Player)")
            .setSingleChoiceItems(names, sel) { d, i ->
                mediaPlayer?.audioTrack = tracks[i].id
                showOsd("Audio: ${tracks[i].name}")
                d.dismiss()
            }
            .setNegativeButton("Cerrar", null).show()
    }

    private fun pickSubtitles() {
        val tracks = mediaPlayer?.spuTracks.orEmpty()
        val current = mediaPlayer?.spuTrack ?: -1
        val options = mutableListOf("Cargar subtítulo externo…", "Desactivados")
        options.addAll(tracks.map { it.name.ifBlank { "Pista de VLC" } })
        val selected = if (current == -1) 1 else {
            val index = tracks.indexOfFirst { it.id == current }
            if (index < 0) 1 else index + 2
        }
        AlertDialog.Builder(this)
            .setTitle("Subtítulos · VLC")
            .setSingleChoiceItems(options.toTypedArray(), selected) { dialog, which ->
                when (which) {
                    0 -> { dialog.dismiss(); pickExternalSubtitle() }
                    1 -> {
                        mediaPlayer?.spuTrack = -1
                        showOsd("Subtítulos desactivados")
                        dialog.dismiss()
                    }
                    else -> {
                        tracks.getOrNull(which - 2)?.let { track ->
                            mediaPlayer?.spuTrack = track.id
                            showOsd("Subtítulo: ${track.name}")
                        }
                        dialog.dismiss()
                    }
                }
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun initGestures() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                val audio = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
                gestureStartVolume = audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                gestureStartBrightness = window.attributes.screenBrightness.takeIf { it >= 0f } ?: 0.5f
                gestureStartTime = mediaPlayer?.time ?: 0L
                gestureMode = ""
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                val start = e1 ?: return false
                val dx = e2.x - start.x
                val dy = e2.y - start.y
                if (gestureMode.isEmpty()) gestureMode =
                    if (kotlin.math.abs(dx) > kotlin.math.abs(dy)) "seek"
                    else if (start.x > videoLayout.width / 2f) "volume" else "brightness"
                when (gestureMode) {
                    "seek" -> {
                        val delta = (dx / videoLayout.width.coerceAtLeast(1) * 120000).toLong()
                        val target = (gestureStartTime + delta).coerceAtLeast(0L)
                        mediaPlayer?.let { p -> p.time = if (p.length > 0) target.coerceAtMost(p.length) else target }
                        showOsd("Búsqueda: " + formatTime(mediaPlayer?.time ?: target))
                    }
                    "volume" -> {
                        val audio = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
                        val max = audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                        val value = (gestureStartVolume - dy / videoLayout.height.coerceAtLeast(1) * max * 1.5f).toInt().coerceIn(0, max)
                        audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, value, 0)
                        showOsd("Volumen: " + (value * 100 / max.coerceAtLeast(1)) + "%")
                    }
                    "brightness" -> {
                        val value = (gestureStartBrightness - dy / videoLayout.height.coerceAtLeast(1) * 1.5f).coerceIn(0.03f, 1f)
                        window.attributes = window.attributes.apply { screenBrightness = value }
                        showOsd("Brillo: " + (value * 100).toInt() + "%")
                    }
                }
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                setControlsVisible(!isControlsVisible)
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val player = mediaPlayer ?: return true
                val delta = seekIntervalSeconds * 1000L
                when {
                    e.x < videoLayout.width * 0.38f -> {
                        player.time = (player.time - delta).coerceAtLeast(0L)
                        showOsd("-${seekIntervalSeconds}s")
                    }
                    e.x > videoLayout.width * 0.62f -> {
                        player.time = (player.time + delta).let { if (player.length > 0) it.coerceAtMost(player.length) else it }
                        showOsd("+${seekIntervalSeconds}s")
                    }
                    else -> showOsd("Toca los laterales dos veces para buscar")
                }
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                showAspectMenu()
            }
        })
        videoLayout.setOnTouchListener { _, event ->
            // Ignore player gestures unless the video surface is the active screen.
            if (isLibraryVisible || videoLayout.visibility != View.VISIBLE) {
                false
            } else {
                gestureDetector.onTouchEvent(event)
                true
            }
        }
    }

    private fun toggleFloatingScreenshot() {
        val prefs = getSharedPreferences("mpm_player", MODE_PRIVATE)
        val enabled = !prefs.getBoolean("floating_screenshot", true)
        prefs.edit().putBoolean("floating_screenshot", enabled).apply()
        btnScreenshotFloat.visibility = if (enabled && isControlsVisible) View.VISIBLE else View.GONE
        showOsd(if (enabled) "Botón flotante de captura activado" else "Botón flotante de captura desactivado")
    }

    private fun showSeekIntervalDialog() {
        val labels = arrayOf("5 segundos", "10 segundos", "15 segundos", "30 segundos")
        val values = intArrayOf(5, 10, 15, 30)
        val selected = values.indexOf(seekIntervalSeconds).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Intervalo de doble toque")
            .setSingleChoiceItems(labels, selected) { dialog, which ->
                seekIntervalSeconds = values[which]
                getSharedPreferences("mpm_player", MODE_PRIVATE).edit()
                    .putInt("seek_interval_seconds", seekIntervalSeconds).apply()
                showOsd("Avance: ${seekIntervalSeconds} segundos")
                dialog.dismiss()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun rememberRecent(uri: Uri) {
                  val prefs = getSharedPreferences("mpm_player", MODE_PRIVATE)
                  val recent = prefs.getString("recent_files_ordered", "")?.split("\\n")?.filter { it.isNotBlank() }?.toMutableList()
                      ?: prefs.getStringSet("recent_files", emptySet())?.toMutableList() ?: mutableListOf()
                  recent.remove(uri.toString()); recent.add(0, uri.toString())
                  val limited = recent.take(30)
                  prefs.edit().putStringSet("recent_files", limited.toSet())
                      .putString("recent_files_ordered", limited.joinToString("\\n")).apply()
              }
              private fun showRecentFiles() {
                  val recent = getSharedPreferences("mpm_player", MODE_PRIVATE).getStringSet("recent_files", emptySet())?.toList().orEmpty()
                  if (recent.isEmpty()) { AlertDialog.Builder(this).setTitle("Archivos recientes").setMessage("Todavía no hay archivos recientes.").setPositiveButton("Aceptar", null).show(); return }
                  val orderedSaved = getSharedPreferences("mpm_player", MODE_PRIVATE)
                      .getString("recent_files_ordered", "")?.split("\\n")?.filter { it.isNotBlank() }.orEmpty()
                  val ordered = (orderedSaved + recent).distinct().filter { recent.contains(it) }
                  AlertDialog.Builder(this).setTitle("Archivos recientes").setItems(ordered.map { getDisplayName(Uri.parse(it)) }.toTypedArray()) { _, which ->
                      val uri = Uri.parse(ordered[which]); playlist.clear(); playlist.add(uri); playlistIndex = 0; play(uri)
                  }.setNegativeButton("Cerrar", null).show()
              }
              private fun showPlaylist() {
                  if (playlist.isEmpty()) { AlertDialog.Builder(this).setTitle("Lista de reproducción").setMessage("Selecciona varios videos desde Archivo > Abrir video para crear una lista.").setPositiveButton("Aceptar", null).show(); return }
                  val names = playlist.mapIndexed { index, uri -> (if (index == playlistIndex) "▶  " else "") + getDisplayName(uri) }.toTypedArray()
                  AlertDialog.Builder(this).setTitle("Lista de reproducción (" + playlist.size + ")").setItems(names) { _, which ->
                      playlistIndex = which; play(playlist[which], 0L)
                  }.setNegativeButton("Cerrar", null).show()
              }
              private fun showMediaInfo() {
                  val uri = currentUri ?: run { Toast.makeText(this, "Primero abre un archivo", Toast.LENGTH_SHORT).show(); return }
                  Thread {
                      val details = StringBuilder()
                      try {
                          val retriever = MediaMetadataRetriever()
                          try {
                              retriever.setDataSource(this, uri)
                              fun meta(key: Int, fallback: String = "No disponible"): String = retriever.extractMetadata(key)?.takeIf { it.isNotBlank() } ?: fallback
                              details.append("Archivo: ").append(getDisplayName(uri)).append("\n")
                              details.append("Duración: ").append(formatTime(meta(MediaMetadataRetriever.METADATA_KEY_DURATION, "0").toLongOrNull() ?: 0L)).append("\n")
                              details.append("Resolución: ").append(meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)).append(" × ").append(meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)).append("\n")
                              details.append("Códecs: información no disponible en esta versión de Android\\n")
                              details.append("Bitrate: ").append(meta(MediaMetadataRetriever.METADATA_KEY_BITRATE)).append(" bps")
                          } finally { retriever.release() }
                      } catch (e: Exception) { details.append("No se pudo leer toda la información del medio.\n").append(e.message ?: "Error desconocido") }
                      runOnUiThread { AlertDialog.Builder(this).setTitle("Información del medio").setMessage(details.toString()).setPositiveButton("Cerrar", null).show() }
                  }.start()
              }

private fun showOsd(msg: String) {
        txtOsd.text = msg
        txtOsd.visibility = View.VISIBLE
        handler.removeCallbacks(osdRunnable)
        handler.postDelayed(osdRunnable, 1600)
    }

    private val osdRunnable = Runnable { txtOsd.visibility = View.GONE }

    private fun updateDecoderUi() {
        val refresh = displayRefreshRateLabel()
        if (isHw) {
            btnHwSw.text = "HW"
            btnHwSw.setTextColor(0xFF2ECC71.toInt())
            txtDecoderInfo.text = "[HW | " + refresh + "]"
        } else {
            btnHwSw.text = "SW"
            btnHwSw.setTextColor(0xFFE67E22.toInt())
            txtDecoderInfo.text = "[SW | " + refresh + "]"
        }
    }

    private fun formatTime(ms: Long): String {
        val s = (ms / 1000) % 60
        val m = (ms / 60000) % 60
        val h = ms / 3600000
        return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(controlsHideRunnable)
        savePlaybackProgress()
        if (subtitlePickerPending) {
            resumeAfterSubtitlePicker = mediaPlayer?.isPlaying == true || resumeAfterSubtitlePicker
        }
        if (filePickerPending) {
            resumeAfterFilePicker = mediaPlayer?.isPlaying == true || resumeAfterFilePicker
        }
        mediaPlayer?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRendererDiscovery()
        mediaPlayer?.setRenderer(null)
        mediaPlayer?.stop()
        mediaPlayer?.release()
        releaseCurrentAssetDescriptor()
        libVLC?.release()
    }
}
