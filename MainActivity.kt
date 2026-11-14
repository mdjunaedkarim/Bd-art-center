package com.example.ardrawing

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import org.opencv.android.OpenCVLoader
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max

class MainActivity : ComponentActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var overlay: OverlayView
    private lateinit var statusText: TextView

    private val tracker = PaperTracker()
    private lateinit var analysisExecutor: ExecutorService

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else {
                Toast.makeText(this, R.string.camera_denied, Toast.LENGTH_LONG).show()
            }
        }

    private val imagePicker =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) loadImage(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (!OpenCVLoader.initLocal()) {
            Toast.makeText(this, R.string.opencv_failed, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        previewView = findViewById(R.id.previewView)
        overlay = findViewById(R.id.overlayView)
        statusText = findViewById(R.id.statusText)
        analysisExecutor = Executors.newSingleThreadExecutor()

        statusText.setText(R.string.need_image)

        findViewById<Button>(R.id.pickButton).setOnClickListener {
            imagePicker.launch("image/*")
        }
        findViewById<Button>(R.id.rotateButton).setOnClickListener {
            overlay.rotateImage()
        }
        findViewById<Button>(R.id.resetButton).setOnClickListener {
            tracker.reset()
            overlay.resetLock()
        }
        findViewById<SeekBar>(R.id.opacitySeek).setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    overlay.imageAlpha = p / 100f
                }

                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            }
        )
        overlay.imageAlpha = 0.5f

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()

            // প্রিভিউ ও বিশ্লেষণ দুটোই ৪:৩ — যাতে কোণার জায়গা স্ক্রিনে ঠিক মেলে
            val selector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .build()

            val preview = Preview.Builder()
                .setResolutionSelector(selector)
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }

            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(selector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            analysis.setAnalyzer(analysisExecutor) { image ->
                try {
                    val result = tracker.detect(image)
                    runOnUiThread {
                        overlay.update(result?.corners, result?.aspect ?: 0.75f)
                        updateStatus()
                    }
                } finally {
                    image.close()
                }
            }

            provider.unbindAll()
            provider.bindToLifecycle(
                this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
            )
        }, ContextCompat.getMainExecutor(this))
    }

    private fun updateStatus() {
        when {
            overlay.bitmap == null -> {
                statusText.setText(R.string.need_image)
                statusText.visibility = TextView.VISIBLE
            }
            overlay.isTracking -> statusText.visibility = TextView.GONE
            else -> {
                statusText.setText(R.string.searching)
                statusText.visibility = TextView.VISIBLE
            }
        }
    }

    private fun loadImage(uri: Uri) {
        try {
            val source = ImageDecoder.createSource(contentResolver, uri)
            val bmp: Bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                // বেশি বড় ছবি ছোট করি, যাতে মেমরি ফুরিয়ে না যায়
                val longest = max(info.size.width, info.size.height)
                if (longest > 2048) {
                    val k = 2048f / longest
                    decoder.setTargetSize(
                        (info.size.width * k).toInt(),
                        (info.size.height * k).toInt()
                    )
                }
            }
            overlay.bitmap = bmp
            updateStatus()
        } catch (e: Exception) {
            Toast.makeText(this, R.string.image_failed, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::analysisExecutor.isInitialized) analysisExecutor.shutdown()
    }
}
