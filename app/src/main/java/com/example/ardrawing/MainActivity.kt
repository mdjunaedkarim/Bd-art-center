package com.example.ardrawing

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import org.opencv.android.OpenCVLoader
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    private enum class Screen { HOME, CREATE, CAMERA, CONGRATS, HISTORY, SETTINGS }

    private var screen = Screen.HOME
    private lateinit var rootContainer: FrameLayout
    private lateinit var analysisExecutor: ExecutorService
    private val tracker = PlaneTracker()

    private var rawBitmap: Bitmap? = null
    private var processedBitmap: Bitmap? = null
    private var brightness = 0
    private var thickness = 0
    private var removeBg = false
    private var opacityPercent = 70

    private var cameraProvider: ProcessCameraProvider? = null
    private var overlayView: OverlayView? = null
    private var cameraHintView: TextView? = null
    private var lockButton: Button? = null

    private val prefs by lazy { getSharedPreferences("ar_draw_prefs", MODE_PRIVATE) }
    private val historyDir by lazy { File(filesDir, "history").apply { mkdirs() } }

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) loadImageFromUri(uri)
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            showScreen(Screen.CAMERA)
        } else {
            Toast.makeText(this, L.t("camera_denied"), Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OpenCVLoader.initLocal()
        L.lang = prefs.getString("lang", "bn") ?: "bn"
        analysisExecutor = Executors.newSingleThreadExecutor()

        rootContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#0F1218"))
        }
        setContentView(rootContainer)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when (screen) {
                    Screen.HOME -> finish()
                    Screen.CAMERA -> {
                        stopCamera()
                        showScreen(Screen.CREATE)
                    }
                    else -> showScreen(Screen.HOME)
                }
            }
        })

        showScreen(Screen.HOME)
    }

    private fun showScreen(target: Screen) {
        if (screen == Screen.CAMERA && target != Screen.CAMERA) {
            stopCamera()
        }
        screen = target
        rootContainer.removeAllViews()

        val view = when (target) {
            Screen.HOME -> buildHomeView()
            Screen.CREATE -> buildCreateView()
            Screen.CAMERA -> buildCameraView()
            Screen.CONGRATS -> buildCongratsView()
            Screen.HISTORY -> buildHistoryView()
            Screen.SETTINGS -> buildSettingsView()
        }
        rootContainer.addView(view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    private fun buildHomeView(): View {
        val scroll = ScrollView(this)
        val col = linearCol(paddingDp = 20)

        col.addView(TextView(this).apply {
            text = L.t("app")
            textSize = 28f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
        })
        col.addView(TextView(this).apply {
            text = L.t("home_sub")
            textSize = 14f
            setTextColor(Color.parseColor("#B0B8C8"))
            setPadding(0, dp(6), 0, dp(18))
        })

        col.addView(TextView(this).apply {
            text = L.t("samples")
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#E2E8F0"))
            setPadding(0, 0, 0, dp(10))
        })

        val sampleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (i in 0 until Samples.COUNT) {
            val thumb = Samples.render(i, 260)
            val card = FrameLayout(this).apply {
                background = roundedBg("#FFFFFF", 14)
                setPadding(dp(6), dp(6), dp(6), dp(6))
                val iv = ImageView(this@MainActivity).apply {
                    setImageBitmap(thumb)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }
                addView(iv, FrameLayout.LayoutParams(dp(96), dp(96)))
                setOnClickListener {
                    rawBitmap = Samples.render(i, 900)
                    brightness = 0
                    thickness = 0
                    removeBg = false
                    processedBitmap = null
                    showScreen(Screen.CREATE)
                }
            }
            val lp = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                marginEnd = dp(10)
            }
            sampleRow.addView(card, lp)
        }
        col.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(sampleRow)
            setPadding(0, 0, 0, dp(22))
        })

        col.addView(actionBtn(L.t("create"), "#4F46E5") { showScreen(Screen.CREATE) })
        col.addView(actionBtn(L.t("history"), "#1E293B") { showScreen(Screen.HISTORY) })
        col.addView(actionBtn(L.t("store"), "#1E293B") {
            Toast.makeText(this, L.t("store_soon"), Toast.LENGTH_SHORT).show()
        })
        col.addView(actionBtn(L.t("settings"), "#1E293B") { showScreen(Screen.SETTINGS) })

        scroll.addView(col)
        return scroll
    }

    private fun buildCreateView(): View {
        val scroll = ScrollView(this)
        val col = linearCol(paddingDp = 18)

        col.addView(topBar(L.t("create")) { showScreen(Screen.HOME) })

        val previewBox = FrameLayout(this).apply {
            background = roundedBg("#1B2130", 16)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val previewIv = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val emptyTv = TextView(this).apply {
            text = L.t("no_photo")
            setTextColor(Color.parseColor("#8A94A6"))
            gravity = Gravity.CENTER
        }
        previewBox.addView(emptyTv, FrameLayout.LayoutParams(MATCH_PARENT, dp(240)))
        previewBox.addView(previewIv, FrameLayout.LayoutParams(MATCH_PARENT, dp(240)))
        col.addView(previewBox, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            bottomMargin = dp(14)
        })

        fun refreshPreview() {
            val src = rawBitmap
            if (src == null) {
                emptyTv.visibility = View.VISIBLE
                previewIv.setImageBitmap(null)
                processedBitmap = null
            } else {
                emptyTv.visibility = View.GONE
                val out = ImageProcessor.process(src, brightness, thickness, removeBg)
                processedBitmap = out
                previewIv.setImageBitmap(out)
            }
        }
        refreshPreview()

        col.addView(actionBtn(L.t("upload"), "#2563EB") {
            pickImageLauncher.launch("image/*")
        })

        col.addView(labelTv(L.t("brightness")))
        col.addView(SeekBar(this).apply {
            max = 100
            progress = brightness + 50
            setOnSeekBarChangeListener(simpleSeek { p ->
                brightness = p - 50
                refreshPreview()
            })
        })

        col.addView(labelTv(L.t("thickness")))
        col.addView(SeekBar(this).apply {
            max = 4
            progress = thickness
            setOnSeekBarChangeListener(simpleSeek { p ->
                thickness = p
                refreshPreview()
            })
        })

        val bgRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(16))
        }
        val bgLabel = TextView(this).apply {
            text = L.t("remove_bg")
            setTextColor(Color.WHITE)
            textSize = 14f
        }
        val bgSwitch = Switch(this).apply {
            isChecked = removeBg
            setOnCheckedChangeListener { _, checked ->
                removeBg = checked
                refreshPreview()
            }
        }
        bgRow.addView(bgLabel, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        bgRow.addView(bgSwitch)
        col.addView(bgRow)

        col.addView(actionBtn(L.t("next"), "#10B981") {
            if (processedBitmap == null) {
                Toast.makeText(this, L.t("need_image"), Toast.LENGTH_SHORT).show()
            } else {
                ensureCameraAndOpen()
            }
        })

        scroll.addView(col)
        return scroll
    }

    private fun buildCameraView(): View {
        val root = FrameLayout(this)

        val previewView = PreviewView(this)
        val overlay = OverlayView(this).apply {
            setBitmap(processedBitmap)
            setOpacity(opacityPercent)
        }
        overlayView = overlay

        val hintTv = TextView(this).apply {
            text = L.t("hint_place")
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            background = roundedBg("#AA000000", 12)
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        cameraHintView = hintTv

        val backBtn = Button(this).apply {
            text = "←"
            setTextColor(Color.WHITE)
            background = roundedBg("#AA111827", 20)
            setOnClickListener {
                stopCamera()
                showScreen(Screen.CREATE)
            }
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(36), dp(12), dp(8))
            addView(backBtn, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(8) })
            addView(hintTv, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }

        val bottomPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg("#CC0F172A", 18)
            setPadding(dp(14), dp(10), dp(14), dp(14))
        }

        val opLabel = TextView(this).apply {
            text = L.t("opacity")
            setTextColor(Color.WHITE)
            textSize = 12f
        }
        val opSeek = SeekBar(this).apply {
            max = 100
            progress = opacityPercent
            setOnSeekBarChangeListener(simpleSeek { p ->
                opacityPercent = p.coerceAtLeast(10)
                overlay.setOpacity(opacityPercent)
            })
        }

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }

        val lockBtn = Button(this).apply {
            text = L.t("lock")
            setTextColor(Color.WHITE)
            background = roundedBg("#2563EB", 12)
            setOnClickListener {
                if (!overlay.isLocked) {
                    tracker.startLock()
                    overlay.lock()
                    text = L.t("unlock")
                    background = roundedBg("#D97706", 12)
                    hintTv.text = L.t("hint_locked")
                } else {
                    tracker.stopLock()
                    overlay.unlock()
                    text = L.t("lock")
                    background = roundedBg("#2563EB", 12)
                    hintTv.text = L.t("hint_place")
                }
            }
        }
        lockButton = lockBtn

        val finishBtn = Button(this).apply {
            text = L.t("finish")
            setTextColor(Color.WHITE)
            background = roundedBg("#10B981", 12)
            setOnClickListener {
                saveToHistory(processedBitmap)
                stopCamera()
                showScreen(Screen.CONGRATS)
            }
        }

        btnRow.addView(lockBtn, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(8) })
        btnRow.addView(finishBtn, LinearLayout.LayoutParams(0, dp(46), 1f))

        bottomPanel.addView(opLabel)
        bottomPanel.addView(opSeek)
        bottomPanel.addView(btnRow)

        root.addView(previewView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(overlay, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(topRow, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))
        root.addView(bottomPanel, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM).apply {
            setMargins(dp(12), 0, dp(12), dp(18))
        })

        startCamera(previewView)
        return root
    }

    private fun buildCongratsView(): View {
        val col = linearCol(paddingDp = 28).apply { gravity = Gravity.CENTER }
        col.addView(TextView(this).apply {
            text = L.t("congrats")
            textSize = 26f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        })
        col.addView(TextView(this).apply {
            text = L.t("congrats_sub")
            textSize = 15f
            setTextColor(Color.parseColor("#CBD5E1"))
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(28))
        })
        col.addView(actionBtn(L.t("go_home"), "#4F46E5") { showScreen(Screen.HOME) })
        return col
    }

    private fun buildHistoryView(): View {
        val scroll = ScrollView(this)
        val col = linearCol(paddingDp = 18)

        col.addView(topBar(L.t("history")) { showScreen(Screen.HOME) })

        val files = (historyDir.listFiles()?.toList() ?: emptyList())
            .sortedByDescending { it.lastModified() }

        col.addView(TextView(this).apply {
            text = String.format(L.t("history_count"), files.size)
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 14f
            setPadding(0, 0, 0, dp(14))
        })

        if (files.isEmpty()) {
            col.addView(TextView(this).apply {
                text = L.t("history_empty")
                setTextColor(Color.parseColor("#CBD5E1"))
                textSize = 15f
                gravity = Gravity.CENTER
                setPadding(0, dp(40), 0, 0)
            })
        } else {
            var row: LinearLayout? = null
            files.forEachIndexed { idx, file ->
                if (idx % 2 == 0) {
                    row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                    col.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                        bottomMargin = dp(10)
                    })
                }
                val bmp = BitmapFactory.decodeFile(file.absolutePath)
                val card = FrameLayout(this).apply {
                    background = roundedBg("#1E293B", 12)
                    setPadding(dp(6), dp(6), dp(6), dp(6))
                    val iv = ImageView(this@MainActivity).apply {
                        setImageBitmap(bmp)
                        scaleType = ImageView.ScaleType.FIT_CENTER
                    }
                    addView(iv, FrameLayout.LayoutParams(MATCH_PARENT, dp(140)))
                    setOnClickListener {
                        if (bmp != null) {
                            rawBitmap = bmp
                            processedBitmap = null
                            brightness = 0
                            thickness = 0
                            removeBg = false
                            showScreen(Screen.CREATE)
                        }
                    }
                }
                row?.addView(card, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                    if (idx % 2 == 0) marginEnd = dp(8)
                })
            }
        }

        scroll.addView(col)
        return scroll
    }

    private fun buildSettingsView(): View {
        val scroll = ScrollView(this)
        val col = linearCol(paddingDp = 18)

        col.addView(topBar(L.t("settings")) { showScreen(Screen.HOME) })

        col.addView(labelTv(L.t("language")))
        val langRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, dp(16))
        }
        listOf("bn" to "বাংলা", "en" to "English", "hi" to "हिन्दी").forEach { (code, name) ->
            val selected = L.lang == code
            val b = Button(this).apply {
                text = name
                setTextColor(Color.WHITE)
                background = roundedBg(if (selected) "#4F46E5" else "#1E293B", 10)
                setOnClickListener {
                    L.lang = code
                    prefs.edit().putString("lang", code).apply()
                    showScreen(Screen.SETTINGS)
                }
            }
            langRow.addView(b, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(6) })
        }
        col.addView(langRow)

        col.addView(actionBtn(L.t("privacy"), "#1E293B") {
            AlertDialog.Builder(this)
                .setTitle(L.t("privacy"))
                .setMessage(L.t("privacy_text"))
                .setPositiveButton("OK", null)
                .show()
        })
        col.addView(actionBtn(L.t("about"), "#1E293B") {
            AlertDialog.Builder(this)
                .setTitle(L.t("about"))
                .setMessage(L.t("about_text"))
                .setPositiveButton("OK", null)
                .show()
        })
        col.addView(actionBtn(L.t("share"), "#2563EB") {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, L.t("share_text"))
            }
            startActivity(Intent.createChooser(intent, L.t("share")))
        })

        col.addView(TextView(this).apply {
            text = L.t("version")
            setTextColor(Color.parseColor("#64748B"))
            gravity = Gravity.CENTER
            setPadding(0, dp(20), 0, 0)
        })

        scroll.addView(col)
        return scroll
    }

    private fun ensureCameraAndOpen() {
        val ok = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (ok) showScreen(Screen.CAMERA)
        else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera(previewView: PreviewView) {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                analysis.setAnalyzer(analysisExecutor) { image ->
                    try {
                        val out = tracker.process(image)
                        val ov = overlayView
                        if (ov != null && ov.isLocked) {
                            runOnUiThread {
                                ov.updateTracking(out)
                                cameraHintView?.text =
                                    if (out.good) L.t("hint_locked") else L.t("hint_lost")
                            }
                        }
                    } finally {
                        image.close()
                    }
                }

                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
            } catch (_: Exception) {
                Toast.makeText(this, L.t("camera_error"), Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCamera() {
        tracker.stopLock()
        cameraProvider?.unbindAll()
        overlayView = null
        cameraHintView = null
        lockButton = null
    }

    private fun loadImageFromUri(uri: Uri) {
        try {
            val source = ImageDecoder.createSource(contentResolver, uri)
            val bmp = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val maxSide = maxOf(info.size.width, info.size.height)
                if (maxSide > 1080) {
                    val scale = 1080f / maxSide
                    decoder.setTargetSize(
                        (info.size.width * scale).toInt().coerceAtLeast(1),
                        (info.size.height * scale).toInt().coerceAtLeast(1)
                    )
                }
            }
            rawBitmap = if (bmp.config == Bitmap.Config.ARGB_8888) bmp else bmp.copy(Bitmap.Config.ARGB_8888, false)
            brightness = 0
            thickness = 0
            removeBg = false
            showScreen(Screen.CREATE)
        } catch (_: Exception) {
            Toast.makeText(this, L.t("image_failed"), Toast.LENGTH_LONG).show()
        }
    }

    private fun saveToHistory(bmp: Bitmap?) {
        if (bmp == null) return
        try {
            val scale = 320f / maxOf(bmp.width, bmp.height).coerceAtLeast(1)
            val thumb = Bitmap.createScaledBitmap(
                bmp,
                (bmp.width * scale).toInt().coerceAtLeast(1),
                (bmp.height * scale).toInt().coerceAtLeast(1),
                true
            )
            val file = File(historyDir, "draw_${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { out ->
                thumb.compress(Bitmap.CompressFormat.PNG, 90, out)
            }
        } catch (_: Exception) {
        }
    }

    private fun linearCol(paddingDp: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(paddingDp), dp(36), dp(paddingDp), dp(24))
    }

    private fun topBar(title: String, onBack: () -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(16))
            addView(Button(this@MainActivity).apply {
                text = L.t("back")
                setTextColor(Color.WHITE)
                background = roundedBg("#1E293B", 10)
                setOnClickListener { onBack() }
            }, LinearLayout.LayoutParams(WRAP_CONTENT, dp(40)).apply { marginEnd = dp(12) })
            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 20f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.WHITE)
            })
        }
    }

    private fun actionBtn(label: String, colorHex: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 15f
            isAllCaps = false
            background = roundedBg(colorHex, 12)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(50)).apply {
                bottomMargin = dp(10)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun labelTv(textStr: String) = TextView(this).apply {
        text = textStr
        setTextColor(Color.parseColor("#CBD5E1"))
        textSize = 13f
        setPadding(0, dp(8), 0, dp(4))
    }

    private fun roundedBg(colorHex: String, radiusDp: Int) = GradientDrawable().apply {
        setColor(Color.parseColor(colorHex))
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun simpleSeek(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
            if (fromUser) onChange(progress)
        }
        override fun onStartTrackingTouch(sb: SeekBar?) {}
        override fun onStopTrackingTouch(sb: SeekBar?) {}
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        super.onDestroy()
        stopCamera()
        if (::analysisExecutor.isInitialized) analysisExecutor.shutdown()
    }
}
