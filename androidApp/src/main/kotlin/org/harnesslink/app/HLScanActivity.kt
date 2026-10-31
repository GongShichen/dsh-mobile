package org.harnesslink.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Full-frame, bundled on-device QR recognition. No model download or Google Play Services required. */
class HLScanActivity : ComponentActivity() {
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val scanner = BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var finished = false
    private var frames = 0
    private lateinit var preview: PreviewView
    private lateinit var status: TextView
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else finishError("请在系统设置中允许相机访问后重试")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i("HarnessLink", "scanner.camerax.created")
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        preview = PreviewView(this).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE; scaleType = PreviewView.ScaleType.FILL_CENTER }
        root.addView(preview, FrameLayout.LayoutParams(-1, -1))
        val controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(48), dp(20), dp(24)); setBackgroundColor(0x66000000) }
        val close = Button(this).apply { text = "取消"; setTextColor(Color.WHITE); setBackgroundColor(Color.TRANSPARENT); setOnClickListener { cancelScan() } }
        controls.addView(close, LinearLayout.LayoutParams(dp(72), dp(48)))
        status = TextView(this).apply { text = "扫描电脑上的配对二维码"; textSize = 18f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setPadding(0, dp(20), 0, dp(8)) }
        controls.addView(status, LinearLayout.LayoutParams(-1, -2))
        controls.addView(TextView(this).apply { text = "将完整二维码放入画面，点击画面可对焦"; textSize = 13f; setTextColor(Color.LTGRAY); gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, -2))
        root.addView(controls, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            controls.setPadding(dp(20), insets.getInsets(WindowInsetsCompat.Type.systemBars()).top + dp(8), dp(20), dp(20)); insets
        }
        setContentView(root)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) { override fun handleOnBackPressed() = cancelScan() })
        preview.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                val point = preview.meteringPointFactory.createPoint(event.x, event.y)
                camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(point).setAutoCancelDuration(3, TimeUnit.SECONDS).build())
                Log.i("HarnessLink", "scanner.focus.requested")
                true
            } else true
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else permission.launch(Manifest.permission.CAMERA)
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (isFinishing || isDestroyed) return@addListener
            try {
                provider = future.get()
                val view = Preview.Builder().build().also { it.surfaceProvider = preview.surfaceProvider }
                val analysis = ImageAnalysis.Builder().setTargetResolution(Size(1920, 1080)).setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysis.setAnalyzer(analysisExecutor) { frame ->
                    val image = frame.image
                    if (image == null || finished) { frame.close(); return@setAnalyzer }
                    frames++
                    if (frames == 1 || frames % 60 == 0) Log.i("HarnessLink", "scanner.frames=$frames size=${frame.width}x${frame.height}")
                    scanner.process(InputImage.fromMediaImage(image, frame.imageInfo.rotationDegrees))
                        .addOnSuccessListener { codes ->
                            val value = codes.firstOrNull { it.rawValue?.startsWith("dsh-mobile://pair?") == true }?.rawValue
                            if (value != null && !finished) {
                                finished = true
                                Log.i("HarnessLink", "scanner.decoded length=${value.length} frames=$frames")
                                status.text = "已识别，正在连接…"
                                preview.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                                setResult(Activity.RESULT_OK, Intent().putExtra(RESULT_QR, value)); finish()
                            } else if (codes.isNotEmpty()) status.text = "请扫描 DSH 移动访问的配对二维码"
                        }
                        .addOnFailureListener { error -> Log.w("HarnessLink", "scanner.decode.failed ${error.javaClass.simpleName}") }
                        .addOnCompleteListener { frame.close() }
                }
                provider?.unbindAll()
                camera = provider?.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, view, analysis)
                Log.i("HarnessLink", "scanner.camera.ready")
            } catch (error: Exception) { Log.e("HarnessLink", "scanner.camera.failed ${error.javaClass.simpleName}"); finishError("无法打开相机，请关闭其他使用相机的应用后重试") }
        }, ContextCompat.getMainExecutor(this))
    }
    private fun cancelScan() { Log.i("HarnessLink", "scanner.cancelled"); setResult(Activity.RESULT_CANCELED); finish() }
    private fun finishError(message: String) { setResult(Activity.RESULT_CANCELED, Intent().putExtra(RESULT_ERROR, message)); finish() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    override fun onDestroy() { provider?.unbindAll(); scanner.close(); analysisExecutor.shutdown(); super.onDestroy() }
    companion object { const val RESULT_QR = "dsh.pairing.qr"; const val RESULT_ERROR = "dsh.scan.error" }
}
