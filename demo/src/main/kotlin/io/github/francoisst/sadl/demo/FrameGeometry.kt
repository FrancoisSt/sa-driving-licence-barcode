package io.github.francoisst.sadl.demo

import android.graphics.Rect
import android.graphics.RectF

/**
 * The guide is kept as a rectangle normalised to the *upright* camera frame (0..1 on each axis). The overlay maps it
 * onto the letterboxed preview, and the analyzer maps it into the raw sensor buffer as the crop to decode.
 * Decoding only the guide's area keeps each read fast; the 5% margin keeps a barcode at the guide's edge readable.
 */
object FrameGeometry {

    /** A barcode-shaped guide centred in an upright frame of [width] x [height] pixels. */
    fun guideFor(width: Int, height: Int): RectF {
        val w = 0.92f * width
        val h = minOf(w / 2.2f, 0.5f * height) // the symbol is about 4.3:1; leave room to aim
        val left = (width - w) / 2f
        val top = (height - h) / 2f
        return RectF(left / width, top / height, (left + w) / width, (top + h) / height)
    }

    /**
     * [guide] (normalised, upright) to a crop in the raw buffer of a [rawWidth] x [rawHeight] frame whose content
     * must be turned [rotationDegrees] clockwise to be upright.
     */
    fun cropInRaw(guide: RectF, rawWidth: Int, rawHeight: Int, rotationDegrees: Int, margin: Float = 0.05f): Rect {
        val g = RectF(guide).apply { inset(-margin * width(), -margin * height()) }
        // Map the two corners from upright to raw normalised coordinates, then take their bounds.
        fun toRaw(u: Float, v: Float): Pair<Float, Float> = when (((rotationDegrees % 360) + 360) % 360) {
            90 -> Pair(v, 1f - u)
            180 -> Pair(1f - u, 1f - v)
            270 -> Pair(1f - v, u)
            else -> Pair(u, v)
        }
        val (x1, y1) = toRaw(g.left, g.top)
        val (x2, y2) = toRaw(g.right, g.bottom)
        val left = (minOf(x1, x2).coerceIn(0f, 1f) * rawWidth).toInt()
        val top = (minOf(y1, y2).coerceIn(0f, 1f) * rawHeight).toInt()
        val right = (maxOf(x1, x2).coerceIn(0f, 1f) * rawWidth).toInt()
        val bottom = (maxOf(y1, y2).coerceIn(0f, 1f) * rawHeight).toInt()
        return Rect(left, top, right, bottom)
    }

    /** The inverse of [cropInRaw]'s mapping: a raw-buffer rectangle as a rectangle normalised to the upright frame. */
    fun rawToUpright(raw: Rect, rawWidth: Int, rawHeight: Int, rotationDegrees: Int): RectF {
        fun toUpright(x: Float, y: Float): Pair<Float, Float> = when (((rotationDegrees % 360) + 360) % 360) {
            90 -> Pair(1f - y, x)
            180 -> Pair(1f - x, 1f - y)
            270 -> Pair(y, 1f - x)
            else -> Pair(x, y)
        }
        val (u1, v1) = toUpright(raw.left.toFloat() / rawWidth, raw.top.toFloat() / rawHeight)
        val (u2, v2) = toUpright(raw.right.toFloat() / rawWidth, raw.bottom.toFloat() / rawHeight)
        return RectF(minOf(u1, u2), minOf(v1, v2), maxOf(u1, u2), maxOf(v1, v2))
    }

    /** Where an upright frame of [frameWidth] x [frameHeight] appears in a view with FIT_CENTER (letterboxed). */
    fun fitCenter(frameWidth: Int, frameHeight: Int, viewWidth: Int, viewHeight: Int): RectF {
        val scale = minOf(viewWidth.toFloat() / frameWidth, viewHeight.toFloat() / frameHeight)
        val w = frameWidth * scale
        val h = frameHeight * scale
        return RectF((viewWidth - w) / 2f, (viewHeight - h) / 2f, (viewWidth + w) / 2f, (viewHeight + h) / 2f)
    }
}
