package io.github.francoisst.sadl.demo

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * The scanner overlay. It dims the preview around the guide, draws the guide's corner brackets, and:
 *
 * - **Searching**: a scan line sweeps up and down the guide (white).
 * - **Identified**: a barcode is in view but not read yet. A box follows its corners (amber).
 * - **Rejected**: a barcode was read but is not a driving licence, or did not decode (red).
 * - **Recognised**: a licence decoded. The box turns green and pulses, and the guide flashes.
 *
 * Symbol corners come normalised to the upright camera frame (see [FrameReader.Symbol]); the view maps them through
 * the FIT_CENTER letterbox, the same way as the guide.
 */
class GuideOverlayView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    enum class State(val color: Int) {
        SEARCHING(Color.WHITE),
        IDENTIFIED(Color.rgb(255, 193, 7)),
        REJECTED(Color.rgb(244, 67, 54)),
        RECOGNISED(Color.rgb(76, 175, 80)),
    }

    /** The upright analysis frame's size; until the first frame, a portrait 3:4 frame is assumed. */
    var frameSize: Pair<Int, Int> = Pair(3, 4)
        set(value) {
            field = value
            invalidate()
        }

    var state: State = State.SEARCHING
        private set

    /** The guide, normalised to the upright frame. */
    val guide: RectF get() = FrameGeometry.guideFor(frameSize.first, frameSize.second)

    private val density = resources.displayMetrics.density
    private val dim = Paint().apply { color = Color.argb(150, 0, 0, 0) }
    private val bracket = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val boxStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        strokeJoin = Paint.Join.ROUND
    }
    private val boxFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val scanLine = Paint(Paint.ANTI_ALIAS_FLAG)
    private val flash = Paint()
    private val dimPath = Path().apply { fillType = Path.FillType.EVEN_ODD }
    private val boxPath = Path()

    // The box drawn now (view pixels), eased towards the latest corners so it glides instead of jumping.
    private var shown: Array<PointF>? = null
    private var target: Array<PointF>? = null
    private var targetAt = 0L
    private var stateAt = 0L
    private var sweep = 0f

    private val sweeper = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1800
        repeatMode = ValueAnimator.REVERSE
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            sweep = it.animatedValue as Float
            invalidate()
        }
    }

    /** Shows [state], with the symbol's normalised corners when there are any. Main thread. */
    fun show(state: State, corners: List<PointF> = emptyList()) {
        if (state != this.state) stateAt = SystemClock.uptimeMillis()
        this.state = state
        if (corners.size == 4) {
            target = corners.map { toView(it) }.toTypedArray()
            targetAt = SystemClock.uptimeMillis()
            if (shown == null) shown = target!!.map { PointF(it.x, it.y) }.toTypedArray()
        }
        if (state == State.SEARCHING) {
            target = null
            shown = null
        }
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        sweeper.start()
    }

    override fun onDetachedFromWindow() {
        sweeper.cancel()
        super.onDetachedFromWindow()
    }

    private fun frameRect(): RectF = FrameGeometry.fitCenter(frameSize.first, frameSize.second, width, height)

    private fun toView(p: PointF): PointF {
        val f = frameRect()
        return PointF(f.left + p.x * f.width(), f.top + p.y * f.height())
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val f = frameRect()
        val g = guide
        val box = RectF(f.left + g.left * f.width(), f.top + g.top * f.height(), f.left + g.right * f.width(), f.top + g.bottom * f.height())
        val radius = 14f * density
        val color = state.color

        // The dimmed surround.
        dimPath.reset()
        dimPath.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
        dimPath.addRoundRect(box, radius, radius, Path.Direction.CW)
        canvas.drawPath(dimPath, dim)

        // The guide's corner brackets, in the state's colour.
        bracket.color = color
        val len = minOf(box.width(), box.height()) * 0.22f
        drawBrackets(canvas, box, len)

        // Searching: the scan line, a soft band that sweeps the guide.
        if (state == State.SEARCHING || state == State.IDENTIFIED) {
            val y = box.top + radius + sweep * (box.height() - 2 * radius)
            val band = 18f * density
            scanLine.shader = LinearGradient(
                0f, y - band, 0f, y + band,
                intArrayOf(Color.TRANSPARENT, Color.argb(170, Color.red(color), Color.green(color), Color.blue(color)), Color.TRANSPARENT),
                null, Shader.TileMode.CLAMP,
            )
            canvas.drawRect(box.left + radius / 2, y - band, box.right - radius / 2, y + band, scanLine)
        }

        // The box round the barcode, eased towards its latest corners; it fades when the barcode is not seen again.
        val t = target
        val s = shown
        if (t != null && s != null && state != State.SEARCHING) {
            for (i in 0 until 4) {
                s[i].x += (t[i].x - s[i].x) * 0.35f
                s[i].y += (t[i].y - s[i].y) * 0.35f
            }
            val age = now - targetAt
            val alpha = if (state == State.RECOGNISED) 1f else (1f - (age - 600f) / 600f).coerceIn(0f, 1f)
            if (alpha > 0f) {
                val pulse = if (state == State.RECOGNISED) 1f + 0.06f * kotlin.math.sin((now - stateAt) / 70.0).toFloat() else 1f
                val cx = s.sumOf { it.x.toDouble() }.toFloat() / 4
                val cy = s.sumOf { it.y.toDouble() }.toFloat() / 4
                boxPath.reset()
                s.forEachIndexed { i, p ->
                    val x = cx + (p.x - cx) * pulse
                    val y = cy + (p.y - cy) * pulse
                    if (i == 0) boxPath.moveTo(x, y) else boxPath.lineTo(x, y)
                }
                boxPath.close()
                boxFill.color = Color.argb((60 * alpha).toInt(), Color.red(color), Color.green(color), Color.blue(color))
                boxStroke.color = Color.argb((255 * alpha).toInt(), Color.red(color), Color.green(color), Color.blue(color))
                canvas.drawPath(boxPath, boxFill)
                canvas.drawPath(boxPath, boxStroke)
            }
            if (alpha > 0f || state == State.RECOGNISED) postInvalidateOnAnimation()
        }

        // Recognised: a short green flash inside the guide.
        if (state == State.RECOGNISED) {
            val a = (1f - (now - stateAt) / 400f).coerceIn(0f, 1f)
            if (a > 0f) {
                flash.color = Color.argb((110 * a).toInt(), 76, 175, 80)
                canvas.drawRoundRect(box, radius, radius, flash)
                postInvalidateOnAnimation()
            }
        }
    }

    private fun drawBrackets(canvas: Canvas, r: RectF, len: Float) {
        canvas.drawLine(r.left, r.top + len, r.left, r.top, bracket)
        canvas.drawLine(r.left, r.top, r.left + len, r.top, bracket)
        canvas.drawLine(r.right - len, r.top, r.right, r.top, bracket)
        canvas.drawLine(r.right, r.top, r.right, r.top + len, bracket)
        canvas.drawLine(r.right, r.bottom - len, r.right, r.bottom, bracket)
        canvas.drawLine(r.right, r.bottom, r.right - len, r.bottom, bracket)
        canvas.drawLine(r.left + len, r.bottom, r.left, r.bottom, bracket)
        canvas.drawLine(r.left, r.bottom, r.left, r.bottom - len, bracket)
    }
}
