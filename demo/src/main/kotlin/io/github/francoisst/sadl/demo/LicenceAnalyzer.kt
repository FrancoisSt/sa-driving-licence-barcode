package io.github.francoisst.sadl.demo

import android.graphics.PointF
import android.graphics.RectF
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import io.github.francoisst.sadl.BarcodeKind
import io.github.francoisst.sadl.LicenceBarcode
import io.github.francoisst.sadl.LicenceChecks
import io.github.francoisst.sadl.SaLicenceBarcode
import io.github.francoisst.sadl.SaLicenceBarcodeException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reads PDF417 symbols inside the guide on each camera frame, on one analysis thread, and stops at the first barcode
 * that decodes and passes its block checks. Logs outcomes and timings only: never bytes or values.
 */
class LicenceAnalyzer(
    private val guide: () -> RectF,
    private val listener: Listener,
) : ImageAnalysis.Analyzer {

    interface Listener {
        /** Analysis thread: the upright size of the frames being analysed. */
        fun onFrameSize(width: Int, height: Int)

        /** Analysis thread: a PDF417 is in view but could not be read yet. [corners]: see [FrameReader.Symbol]. */
        fun onSeen(corners: List<PointF>)

        /** Analysis thread: a PDF417 was read but is not a valid licence. */
        fun onRejected(kind: BarcodeKind, reason: SaLicenceBarcodeException.Reason, corners: List<PointF>)

        /** Analysis thread: a licence decoded, but a blocking check failed (spec/checks.md): not accepted. */
        fun onChecksFailed(findings: List<LicenceChecks.Finding>, corners: List<PointF>)

        /** Analysis thread: a licence decoded and passed the blocking checks. Called once until [resume]. */
        fun onDecoded(licence: LicenceBarcode, rawSize: Int, corners: List<PointF>)
    }

    private val reader = FrameReader()
    private val done = AtomicBoolean(false)
    private var lastSize = Pair(0, 0)
    private var frames = 0

    /** Scan again after a result. */
    fun resume() = done.set(false)

    override fun analyze(image: ImageProxy) {
        try {
            if (done.get()) return
            val rotation = image.imageInfo.rotationDegrees
            val upright = if (rotation % 180 == 0) Pair(image.width, image.height) else Pair(image.height, image.width)
            if (upright != lastSize) {
                lastSize = upright
                Log.i(TAG, "analysis frames ${image.width}x${image.height}, rotation $rotation")
                listener.onFrameSize(upright.first, upright.second)
            }
            image.setCropRect(FrameGeometry.cropInRaw(guide(), image.width, image.height, rotation))
            val read = reader.read(image)
            frames++
            if (frames % 20 == 0 || read.seenButUnread) {
                Log.i(TAG, "frame $frames: ${read.engine} ${read.ms} ms${if (read.seenButUnread) ", symbol seen, not decoded" else ""}")
            }
            if (read.seenButUnread) listener.onSeen(read.symbols.first().corners)
            for (symbol in read.decoded) {
                val raw = symbol.raw!! // the raw bytes, never the reader's text
                val kind = SaLicenceBarcode.identify(raw)
                try {
                    val licence = SaLicenceBarcode.decode(raw)
                    val findings = when (licence) {
                        is LicenceBarcode.Card -> LicenceChecks.check(licence.licence)
                        is LicenceBarcode.Temporary -> LicenceChecks.check(licence.licence)
                    }
                    if (findings.any { it.blocking }) {
                        Log.i(TAG, "decoded ${kind.name.lowercase()} but checks failed: ${findings.map { it.name.lowercase() }}")
                        listener.onChecksFailed(findings, symbol.corners)
                        continue
                    }
                    if (done.compareAndSet(false, true)) {
                        Log.i(TAG, "decoded ${kind.name.lowercase()} (${raw.size} bytes) by ${read.engine} after $frames frames, ${read.ms} ms")
                        listener.onDecoded(licence, raw.size, symbol.corners)
                    }
                    return
                } catch (e: SaLicenceBarcodeException) {
                    Log.i(TAG, "rejected ${kind.name.lowercase()}: ${e.reason.name.lowercase()} (${raw.size} bytes)")
                    listener.onRejected(kind, e.reason, symbol.corners)
                }
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "frame failed: ${e.javaClass.simpleName}")
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "frame failed: out of memory")
        } finally {
            image.close()
        }
    }

    /** Call on the analysis thread, after the last frame. */
    fun close() = reader.close()

    companion object {
        const val TAG = "SadlDemo"
    }
}
