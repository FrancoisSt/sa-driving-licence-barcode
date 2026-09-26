package io.github.francoisst.sadl.demo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import androidx.camera.core.ImageProxy
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.math.hypot
import zxingcpp.BarcodeReader

/**
 * Reads the PDF417 symbols inside a camera frame's crop rectangle, with two engines:
 *
 * 1. **ML Kit** first. On worn cards and cards in plastic sleeves it reads frames on which zxing-cpp finds the
 *    symbol but fails its error correction. A frame waits for it at most [ML_KIT_WAIT_MS]: ML Kit can spend seconds
 *    on a symbol it cannot read, and while it is busy, frames go to zxing-cpp alone.
 * 2. **zxing-cpp** on every [ZXING_EVERY]th frame, and on every frame ML Kit has no answer for. When zxing-cpp finds
 *    a symbol but cannot correct it, the symbol is warped to a true rectangle using its corners, blurred along its
 *    bars (removing speckle without blurring the bar widths that carry the data), and read again.
 *
 * Both engines give raw bytes. One instance per analysis thread; not thread-safe.
 */
class FrameReader : java.io.Closeable {

    /**
     * A symbol in the frame: its raw bytes, or null when it was found but not decoded, and its corners normalised to
     * the upright frame (top-left, top-right, bottom-right, bottom-left; empty when the engine gave none).
     */
    class Symbol(val raw: ByteArray?, val corners: List<PointF>)

    /** What a frame gave. */
    class Result(val symbols: List<Symbol>, val engine: String, val ms: Long) {
        val decoded: List<Symbol> get() = symbols.filter { it.raw != null }
        val seenButUnread: Boolean get() = decoded.isEmpty() && symbols.isNotEmpty()
    }

    private val mlKit = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_PDF417).build(),
    )
    private var pending: Task<List<Barcode>>? = null
    private var nv21 = ByteArray(0)
    private var frame = 0

    private val zxing = BarcodeReader(
        BarcodeReader.Options().apply {
            formats = setOf(BarcodeReader.Format.PDF_417)
            tryHarder = true
            tryRotate = true
            tryInvert = false
            returnErrors = true // report symbols found but not decoded, with their corners
        },
    )

    fun read(image: ImageProxy): Result {
        val started = android.os.SystemClock.elapsedRealtime()
        fun done(symbols: List<Symbol>, engine: String) = Result(symbols, engine, android.os.SystemClock.elapsedRealtime() - started)

        // 1. ML Kit. A read still running from an earlier frame is collected when it finishes, not waited for.
        var mlKitAnswered = false
        val earlier = pending
        val task = earlier ?: startMlKit(image)
        if (task != null) {
            val barcodes = try {
                Tasks.await(task, if (earlier != null) 0 else ML_KIT_WAIT_MS, TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                null
            } catch (e: Exception) {
                emptyList()
            }
            pending = if (barcodes == null) task else null
            if (barcodes != null) {
                mlKitAnswered = earlier == null
                // ML Kit reports corners in the upright crop (it was given the crop and the frame's rotation).
                val symbols = barcodes.mapNotNull { b ->
                    val raw = b.rawBytes?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                    Symbol(raw, normalise(image, b.cornerPoints.orEmpty().map { PointF(it.x.toFloat(), it.y.toFloat()) }))
                }
                if (symbols.isNotEmpty()) return done(symbols, "ml-kit")
            }
        }

        // 2. zxing-cpp, on its turn or when ML Kit had no answer for this frame.
        if (mlKitAnswered && frame++ % ZXING_EVERY != 0) return done(emptyList(), "ml-kit")
        val results = zxing.read(image)
        val symbols = results.map { r ->
            val corners = r.position?.let { p -> listOf(p.topLeft, p.topRight, p.bottomRight, p.bottomLeft).map { PointF(it.x.toFloat(), it.y.toFloat()) } }
            Symbol(r.bytes?.takeIf { r.error == null && it.isNotEmpty() }, normalise(image, corners.orEmpty()))
        }
        if (symbols.any { it.raw != null }) return done(symbols, "zxing-cpp")
        val failed = results.filter { it.error != null }.mapNotNull { it.position }.maxByOrNull { width(it) }
            ?: return done(symbols, "zxing-cpp")
        val recovered = recover(image, failed)
        if (recovered.isEmpty()) return done(symbols, "zxing-cpp")
        val corners = symbols.firstOrNull { it.corners.isNotEmpty() }?.corners.orEmpty()
        return done(recovered.map { Symbol(it, corners) }, "zxing-cpp recovered")
    }

    /** Points in the upright crop's pixels to the upright frame, normalised. */
    private fun normalise(image: ImageProxy, points: List<PointF>): List<PointF> {
        if (points.size != 4) return emptyList()
        val rot = image.imageInfo.rotationDegrees
        val crop = image.cropRect
        val region = FrameGeometry.rawToUpright(crop, image.width, image.height, rot)
        val cw = (if (rot % 180 == 0) crop.width() else crop.height()).toFloat()
        val ch = (if (rot % 180 == 0) crop.height() else crop.width()).toFloat()
        return points.map { PointF(region.left + it.x / cw * region.width(), region.top + it.y / ch * region.height()) }
    }

    /** ML Kit on the crop only: its luminance as NV21 with neutral chroma (InputImage has no crop of its own). */
    private fun startMlKit(image: ImageProxy): Task<List<Barcode>>? {
        val crop = image.cropRect
        val w = crop.width() and 1.inv()
        val h = crop.height() and 1.inv()
        if (w < 32 || h < 32) return null
        val size = w * h * 3 / 2
        if (nv21.size != size) nv21 = ByteArray(size)
        val plane = image.planes[0]
        val buf = plane.buffer.duplicate()
        for (y in 0 until h) {
            buf.position((crop.top + y) * plane.rowStride + crop.left)
            buf.get(nv21, y * w, w)
        }
        java.util.Arrays.fill(nv21, w * h, size, 128.toByte())
        // The buffer is reused, so no new read starts until this one is done (see `pending`).
        return mlKit.process(InputImage.fromByteArray(nv21, w, h, image.imageInfo.rotationDegrees, InputImage.IMAGE_FORMAT_NV21))
    }

    // --- Recovery of a symbol zxing-cpp found but could not correct ---

    private fun recover(image: ImageProxy, quad: BarcodeReader.Position): List<ByteArray> {
        if (Runtime.getRuntime().maxMemory() < 256L * 1024 * 1024) return emptyList() // small heaps: skip
        val (luma, left, top) = uprightLuma(image, quad) ?: return emptyList()
        val warped = warp(luma, quad, left, top)
        luma.recycle()
        val blurred = verticalBlur(warped, maxOf(3, (warped.height / 60) or 1))
        warped.recycle()
        return try {
            zxing.read(blurred).filter { it.error == null }.mapNotNull { it.bytes?.takeIf { b -> b.isNotEmpty() } }
        } finally {
            blurred.recycle()
        }
    }

    /** The luminance around [p] (upright crop coordinates, as zxing-cpp reports them) as an upright greyscale bitmap. */
    private fun uprightLuma(image: ImageProxy, p: BarcodeReader.Position): Triple<Bitmap, Int, Int>? {
        val crop = image.cropRect
        val rot = image.imageInfo.rotationDegrees
        val cw = if (rot % 180 == 0) crop.width() else crop.height()
        val ch = if (rot % 180 == 0) crop.height() else crop.width()
        val xs = listOf(p.topLeft.x, p.topRight.x, p.bottomRight.x, p.bottomLeft.x)
        val ys = listOf(p.topLeft.y, p.topRight.y, p.bottomRight.y, p.bottomLeft.y)
        val pad = ((ys.max() - ys.min()) / 4) + 16
        val left = (xs.min() - pad).coerceAtLeast(0)
        val top = (ys.min() - pad).coerceAtLeast(0)
        val w = (xs.max() + pad).coerceAtMost(cw) - left
        val h = (ys.max() + pad).coerceAtMost(ch) - top
        if (w < 20 || h < 5) return null
        val plane = image.planes[0]
        val buf = plane.buffer
        val px = IntArray(w * h)
        for (v in 0 until h) {
            for (u in 0 until w) {
                val uu = left + u
                val vv = top + v
                // Upright (uu, vv) back to the unrotated crop's (x, y).
                val (x, y) = when (rot) {
                    90 -> Pair(vv, crop.height() - 1 - uu)
                    180 -> Pair(crop.width() - 1 - uu, crop.height() - 1 - vv)
                    270 -> Pair(crop.width() - 1 - vv, uu)
                    else -> Pair(uu, vv)
                }
                val l = buf.get((crop.top + y) * plane.rowStride + crop.left + x).toInt() and 0xFF
                px[v * w + u] = Color.rgb(l, l, l)
            }
        }
        return Triple(Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888), left, top)
    }

    /** Maps the symbol's four corners onto a true rectangle, with a quiet zone of white around it. */
    private fun warp(src: Bitmap, p: BarcodeReader.Position, left: Int, top: Int): Bitmap {
        val w = maxOf(dist(p.topLeft, p.topRight), dist(p.bottomLeft, p.bottomRight)).toInt().coerceAtLeast(20)
        val h = maxOf(dist(p.topLeft, p.bottomLeft), dist(p.topRight, p.bottomRight)).toInt().coerceAtLeast(5)
        val pad = maxOf(8, h / 8)
        val out = Bitmap.createBitmap(w + 2 * pad, h + 2 * pad, Bitmap.Config.ARGB_8888)
        val from = floatArrayOf(
            (p.topLeft.x - left).toFloat(), (p.topLeft.y - top).toFloat(),
            (p.topRight.x - left).toFloat(), (p.topRight.y - top).toFloat(),
            (p.bottomRight.x - left).toFloat(), (p.bottomRight.y - top).toFloat(),
            (p.bottomLeft.x - left).toFloat(), (p.bottomLeft.y - top).toFloat(),
        )
        val to = floatArrayOf(
            pad.toFloat(), pad.toFloat(), (pad + w).toFloat(), pad.toFloat(),
            (pad + w).toFloat(), (pad + h).toFloat(), pad.toFloat(), (pad + h).toFloat(),
        )
        val m = Matrix().apply { setPolyToPoly(from, 0, to, 0, 4) }
        Canvas(out).apply {
            drawColor(Color.WHITE)
            drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG))
        }
        return out
    }

    /** A box blur down each column: along the bars of a horizontal PDF417, across its rows' speckle. */
    private fun verticalBlur(src: Bitmap, size: Int): Bitmap {
        val w = src.width
        val h = src.height
        val inPx = IntArray(w * h).also { src.getPixels(it, 0, w, 0, 0, w, h) }
        val outPx = IntArray(w * h)
        val r = size / 2
        for (x in 0 until w) {
            var sum = 0
            for (y in -r..r) sum += inPx[y.coerceIn(0, h - 1) * w + x] and 0xFF
            for (y in 0 until h) {
                val g = sum / (2 * r + 1)
                outPx[y * w + x] = Color.rgb(g, g, g)
                sum += (inPx[(y + r + 1).coerceAtMost(h - 1) * w + x] and 0xFF) - (inPx[(y - r).coerceAtLeast(0) * w + x] and 0xFF)
            }
        }
        return Bitmap.createBitmap(outPx, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun width(p: BarcodeReader.Position) = dist(p.topLeft, p.topRight)

    private fun dist(a: android.graphics.Point, b: android.graphics.Point) = hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble())

    override fun close() {
        mlKit.close()
    }

    companion object {
        const val ML_KIT_WAIT_MS = 1500L
        const val ZXING_EVERY = 3
    }
}
