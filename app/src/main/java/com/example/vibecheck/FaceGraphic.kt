package com.example.vibecheck

import android.content.Context
import android.graphics.*
import com.google.mlkit.vision.face.Face

// This class draws a bounding box and emotion label for a detected face.
// The box color depends on the predicted emotion.
class FaceGraphic(
    overlay: GraphicOverlay,
    private val context: Context,
    private val face: Face,
    private val faceBitmap: Bitmap,
    private val mainVibe: String, // <-- now passed from MainActivity
    private val topEmotions: List<Pair<String, Float>>,
    private val offsetX: Float = 0f,
    private val offsetY: Float = 0f
) : GraphicOverlay.Graphic(overlay) {

    private val boxColor: Int = getColorForEmotion(mainVibe)

    override fun draw(canvas: Canvas) {
        val rect = face.boundingBox

        // Scale the bounding box inwards by 10% on each side (slightly smaller)
        val scaleFactor = 0.9f
        val rectWidth = rect.width() * scaleFactor
        val rectHeight = rect.height() * scaleFactor
        val centerX = rect.centerX().toFloat()
        val centerY = rect.centerY().toFloat()
        var left = translateX(centerX - rectWidth / 2)
        var top = translateY(centerY - rectHeight / 2)
        var right = translateX(centerX + rectWidth / 2)
        var bottom = translateY(centerY + rectHeight / 2)

        left += offsetX
        right += offsetX
        top += offsetY
        bottom += offsetY

        val paint = Paint().apply {
            color = boxColor
            style = Paint.Style.STROKE
            strokeWidth = 8f
        }

        val vibeTextPaint = Paint().apply {
            color = boxColor
            textSize = 36f // smaller text size
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            style = Paint.Style.FILL
            isAntiAlias = true
            setShadowLayer(5f, 0f, 0f, Color.BLACK)
        }

        // Draw the bounding box
        canvas.drawRect(left, top, right, bottom, paint)

        // Draw main vibe name in the top-left corner INSIDE the bounding box
        val vibeLabel = mainVibe.replaceFirstChar { it.uppercase() }
        val vibeLabelX = left + 16f // slightly padded from left
        // Calculate text height for baseline alignment
        val fm = vibeTextPaint.fontMetrics
        val vibeLabelY = top + 16f - fm.top // 16f padding from top, baseline-correct

        canvas.drawText(vibeLabel, vibeLabelX, vibeLabelY, vibeTextPaint)
        // No top3 emotions or %s shown anywhere
    }

    private fun getColorForEmotion(emotion: String): Int {
        return when (emotion) {
            "happy" -> Color.parseColor("#4CAF50")
            "angry" -> Color.parseColor("#F44336")
            "neutral" -> Color.WHITE
            "fear" -> Color.parseColor("#90CAF9")
            "surprise" -> Color.parseColor("#9C27B0")
            "disgust" -> Color.parseColor("#FFB74D")
            "sad" -> Color.parseColor("#7893ad")
            else -> Color.LTGRAY
        }
    }
}