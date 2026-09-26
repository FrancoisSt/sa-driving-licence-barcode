package io.github.francoisst.sadl.demo

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.PointF
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.util.TypedValue
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import io.github.francoisst.sadl.BarcodeKind
import io.github.francoisst.sadl.CardLicence
import io.github.francoisst.sadl.LicenceBarcode
import io.github.francoisst.sadl.LicenceChecks
import io.github.francoisst.sadl.SaLicenceBarcodeException
import io.github.francoisst.sadl.TemporaryLicence
import io.github.francoisst.sadl.VehicleCode
import io.github.francoisst.sadl.WiPortrait
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The demo: a camera screen that reads a South African driving-licence barcode and shows what is in it, including
 * the portrait. Everything stays on screen: nothing is stored, logged or sent. A real app would send the raw bytes
 * to its server and decode them again there (see docs/privacy.md).
 */
class MainActivity : ComponentActivity(), LicenceAnalyzer.Listener {

    private lateinit var previewView: PreviewView
    private lateinit var overlay: GuideOverlayView
    private lateinit var status: TextView
    private lateinit var torch: Button
    private lateinit var result: ScrollView

    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val portraitExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var analyzer: LicenceAnalyzer? = null
    private var camera: Camera? = null
    @Volatile private var guide = FrameGeometry.guideFor(3, 4)
    private var lastRejectionAt = 0L
    private var pendingResult: Runnable? = null
    private val backToSearching = Runnable {
        if (result.visibility != View.VISIBLE) {
            overlay.show(GuideOverlayView.State.SEARCHING)
            status.setText(R.string.prompt)
        }
    }

    private val requestCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else status.setText(R.string.camera_needed)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Personal data is shown: keep it out of screenshots, screen recording and the recent-apps thumbnail.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_main)
        previewView = findViewById(R.id.preview)
        overlay = findViewById(R.id.overlay)
        status = findViewById(R.id.status)
        torch = findViewById(R.id.torch)
        result = findViewById(R.id.result)

        // FIT_CENTER shows the whole analysed frame, so what is inside the guide on screen is what is decoded.
        previewView.scaleType = PreviewView.ScaleType.FIT_CENTER
        torch.setOnClickListener {
            val c = camera ?: return@setOnClickListener
            if (c.cameraInfo.hasFlashUnit()) c.cameraControl.enableTorch(c.cameraInfo.torchState.value != 1)
        }
        findViewById<Button>(R.id.scan_again).setOnClickListener { scanAgain() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestCamera.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({ bind(future.get()) }, ContextCompat.getMainExecutor(this))
    }

    private fun bind(provider: ProcessCameraProvider) {
        // 4:3 for both, and the highest analysis resolution: at about 1920 x 1440 a barcode that filled the guide
        // was too few pixels wide to read on a worn card. Resolution matters more than frame rate here.
        val fourByThree = AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
        val preview = Preview.Builder()
            .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(fourByThree).build())
            .build()
            .also { it.surfaceProvider = previewView.surfaceProvider }
        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAllowedResolutionMode(ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE)
                    .setAspectRatioStrategy(fourByThree)
                    .setResolutionStrategy(
                        ResolutionStrategy(Size(4000, 3000), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER),
                    )
                    .build(),
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        val a = LicenceAnalyzer({ guide }, this)
        analysis.setAnalyzer(analysisExecutor, a)
        analyzer = a
        provider.unbindAll()
        camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        Log.i(LicenceAnalyzer.TAG, "camera bound")
    }

    // --- LicenceAnalyzer.Listener (analysis thread) ---

    override fun onFrameSize(width: Int, height: Int) = runOnUiThread {
        overlay.frameSize = Pair(width, height)
        guide = overlay.guide
    }

    override fun onSeen(corners: List<PointF>) = runOnUiThread {
        if (result.visibility == View.VISIBLE || overlay.state == GuideOverlayView.State.RECOGNISED) return@runOnUiThread
        overlay.show(GuideOverlayView.State.IDENTIFIED, corners)
        status.setText(R.string.prompt_misread)
        overlay.removeCallbacks(backToSearching)
        overlay.postDelayed(backToSearching, 1500)
    }

    override fun onRejected(kind: BarcodeKind, reason: SaLicenceBarcodeException.Reason, corners: List<PointF>) = runOnUiThread {
        if (result.visibility == View.VISIBLE || overlay.state == GuideOverlayView.State.RECOGNISED) return@runOnUiThread
        overlay.show(GuideOverlayView.State.REJECTED, corners)
        val now = System.currentTimeMillis()
        if (now - lastRejectionAt > 1500) {
            lastRejectionAt = now
            status.setText(if (kind == BarcodeKind.OTHER) R.string.prompt_other else R.string.prompt_misread)
        }
        overlay.removeCallbacks(backToSearching)
        overlay.postDelayed(backToSearching, 2000)
    }

    override fun onChecksFailed(findings: List<LicenceChecks.Finding>, corners: List<PointF>) = runOnUiThread {
        if (result.visibility == View.VISIBLE || overlay.state == GuideOverlayView.State.RECOGNISED) return@runOnUiThread
        overlay.show(GuideOverlayView.State.REJECTED, corners)
        status.setText(R.string.prompt_checks_failed)
        overlay.removeCallbacks(backToSearching)
        overlay.postDelayed(backToSearching, 2000)
    }

    override fun onDecoded(licence: LicenceBarcode, rawSize: Int, corners: List<PointF>) = runOnUiThread {
        overlay.removeCallbacks(backToSearching)
        overlay.show(GuideOverlayView.State.RECOGNISED, corners)
        status.setText(R.string.recognised)
        camera?.cameraControl?.enableTorch(false)
        // Let the green box and flash register before the result covers the camera.
        val showIt = Runnable { show(licence, rawSize) }
        pendingResult = showIt
        overlay.postDelayed(showIt, 700)
    }

    // --- The result ---

    private fun show(licence: LicenceBarcode, rawSize: Int) {
        pendingResult = null
        val fields = findViewById<LinearLayout>(R.id.fields)
        val portrait = findViewById<ImageView>(R.id.portrait)
        fields.removeAllViews()
        portrait.setImageDrawable(null)
        portrait.visibility = View.GONE
        val title = findViewById<TextView>(R.id.result_title)
        when (licence) {
            is LicenceBarcode.Card -> {
                title.text = "Driving licence card (barcode version ${licence.licence.version})"
                cardRows(licence.licence).forEach { (k, v) -> fields.addView(row(k, v)) }
                decodePortrait(licence.licence, portrait)
            }
            is LicenceBarcode.Temporary -> {
                title.text = "Temporary driving licence"
                temporaryRows(licence.licence).forEach { (k, v) -> fields.addView(row(k, v)) }
            }
        }
        fields.addView(row("Barcode", "$rawSize bytes"))
        result.visibility = View.VISIBLE
        result.scrollTo(0, 0)
        previewView.visibility = View.INVISIBLE
    }

    private fun cardRows(c: CardLicence): List<Pair<String, String>> {
        val findings = LicenceChecks.check(c)
        return listOf(
            "Licence number" to c.licenceNumber,
            "Surname" to c.surname,
            "Initials" to c.initials,
            "ID number" to "${c.idNumber} (${c.idCountry}, type ${c.idType})",
            "Birth date" to c.birthDate.toString(),
            "Gender" to when (c.genderCode) { "01" -> "male"; "02" -> "female"; else -> c.genderCode },
            "Valid" to "${c.validFrom} to ${c.validTo}",
            "Vehicle codes" to c.codes.joinToString("\n") { codeText(it) }.ifEmpty { "none" },
            "Driver restrictions" to driverRestrictions(c),
            "PrDP" to if (c.prdpCategories.isEmpty()) "none" else "${c.prdpCategories.joinToString(",")} until ${c.prdpExpiry ?: "?"}",
            "Licence country" to c.licenceCountry,
            "Issue number" to c.issueNumber,
            "Checks" to if (findings.isEmpty()) "all passed" else findings.joinToString("\n") {
                it.name.lowercase().replace('_', ' ') + if (it.blocking) " (fails)" else " (warning)"
            },
        )
    }

    private fun temporaryRows(t: TemporaryLicence): List<Pair<String, String>> {
        val findings = LicenceChecks.check(t)
        return listOf(
            "Licence number" to t.licenceNumber + "\n(not the \"No.\" printed on the form)",
            "Name" to t.name,
            "ID number" to "${t.idNumber} (type ${t.idType})",
            "Issued" to t.issueDate.toString(),
            "Valid to" to "${t.validTo} (issue date + 6 months)",
            "Vehicle codes" to t.codes.joinToString("\n") { codeText(it) }.ifEmpty { "none" },
            "PrDP" to if (t.prdpCategories.isEmpty()) "none" else "${t.prdpCategories.joinToString(",")} until ${t.prdpExpiry ?: "?"}",
            "Serial" to t.serial,
            "Checks" to if (findings.isEmpty()) "all passed" else findings.joinToString("\n") { it.name.lowercase().replace('_', ' ') },
        )
    }

    private fun codeText(v: VehicleCode): String {
        val restriction = when (v.vehicleRestriction) {
            "", "0" -> ""
            "1" -> ", automatic transmission"
            "2" -> ", electrically powered"
            "3" -> ", physically disabled"
            "4" -> ", bus over 16 000 kg GVM"
            else -> ", restriction ${v.vehicleRestriction}"
        }
        return "${v.code}, since ${v.firstIssue ?: "?"}$restriction"
    }

    private fun driverRestrictions(c: CardLicence): String = buildList {
        if (c.needsCorrectiveLenses) add("glasses or contact lenses")
        if (c.hasArtificialLimb) add("artificial limb")
    }.joinToString(", ").ifEmpty { "none" }

    private fun decodePortrait(card: CardLicence, into: ImageView) {
        val wi = card.photo ?: return
        portraitExecutor.execute {
            val started = System.nanoTime()
            val bitmap: Bitmap? = try {
                portraitBitmap(WiPortrait.decode(wi))
            } catch (e: Exception) {
                Log.i(LicenceAnalyzer.TAG, "portrait failed: ${e.message}")
                null
            }
            if (bitmap != null) Log.i(LicenceAnalyzer.TAG, "portrait decoded in ${(System.nanoTime() - started) / 1_000_000} ms")
            runOnUiThread {
                if (bitmap != null && result.visibility == View.VISIBLE) {
                    into.setImageBitmap(bitmap)
                    into.visibility = View.VISIBLE
                }
            }
        }
    }

    /** 200 x 250 greyscale samples (upright, row by row) as a bitmap. */
    private fun portraitBitmap(grey: ByteArray): Bitmap {
        val argb = IntArray(grey.size) { i ->
            val g = grey[i].toInt() and 0xFF
            (0xFF shl 24) or (g shl 16) or (g shl 8) or g
        }
        return Bitmap.createBitmap(argb, 200, 250, Bitmap.Config.ARGB_8888)
    }

    private fun row(label: String, value: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(6), 0, dp(6))
        addView(TextView(context).apply {
            text = label
            setTextColor(0xFF9AA4AE.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        })
        addView(TextView(context).apply {
            text = value
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTextIsSelectable(false)
        })
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun scanAgain() {
        // Drop what was shown; nothing else holds it.
        findViewById<LinearLayout>(R.id.fields).removeAllViews()
        findViewById<ImageView>(R.id.portrait).setImageDrawable(null)
        result.visibility = View.GONE
        previewView.visibility = View.VISIBLE
        overlay.show(GuideOverlayView.State.SEARCHING)
        status.setText(R.string.prompt)
        analyzer?.resume()
    }

    override fun onStop() {
        super.onStop()
        // Leaving the app clears the result, or the one about to be shown: personal data should not linger on screen.
        if (isChangingConfigurations) return
        val pending = pendingResult
        if (pending != null) {
            overlay.removeCallbacks(pending)
            pendingResult = null
        }
        if (pending != null || result.visibility == View.VISIBLE) scanAgain()
    }

    override fun onDestroy() {
        super.onDestroy()
        val a = analyzer
        analysisExecutor.execute { a?.close() } // on the analysis thread, after the last frame
        analysisExecutor.shutdown()
        portraitExecutor.shutdown()
    }
}
