package com.example.vibecheck

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.Interpreter.Options
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

class VibeClassifier(context: Context) {

    private val inputImageWidth = 48
    private val inputImageHeight = 48
    private val inputChannels = 1
    private val numClasses = 6 // Angry, Disgust, Fear, Happy, Sad, Neutral

    private val interpreter: Interpreter

    // Emotion labels in the same order as model training (NO "surprise")
    private val classLabels = listOf(
        "angry", "disgust", "fear", "happy", "sad", "neutral"
    )

    // For delayed main vibe switching
    private var lastVibe: String = classLabels[0]
    private var lastSwitchTime: Long = 0L

    init {
        // Load model from assets, memory-mapped for efficiency
        val assetFileDescriptor = context.assets.openFd("vibe_model.tflite")
        val fileInputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
        val fileChannel = fileInputStream.channel
        val startOffset = assetFileDescriptor.startOffset
        val declaredLength = assetFileDescriptor.declaredLength
        val modelBuffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)

        // Use multi-threading for faster inference
        val options = Options().apply {
            setNumThreads(2)
        }
        interpreter = Interpreter(modelBuffer, options)
    }

    /**
     * Returns the main vibe using revised logic and a 0.5s transition delay:
     * - "happy", "angry": > 2%
     * - "fear", "disgust", "sad": > 40%
     * - "neutral": > 20%
     * - If multiple emotions over threshold, pick highest value as main vibe (except for "neutral").
     * - If only "neutral" is over threshold, show "neutral".
     * - If none over threshold, pick the vibe with highest probability (never "Unknown").
     * - Main vibe only changes if a new candidate has met the threshold for at least 0.5 second.
     */
    fun classifyEmotionWithThresholds(bitmap: Bitmap): String {
        val imageBuffer = preprocess(bitmap)
        val output = Array(1) { FloatArray(numClasses) }
        interpreter.run(imageBuffer, output)
        val probs = output[0]

        // Thresholds
        val thresholdHappyAngry = 0.02f
        val thresholdFearDisgustSad = 0.450f
        val thresholdNeutral = 0.20f

        // Indices
        val idxAngry = classLabels.indexOf("angry")
        val idxDisgust = classLabels.indexOf("disgust")
        val idxFear = classLabels.indexOf("fear")
        val idxHappy = classLabels.indexOf("happy")
        val idxSad = classLabels.indexOf("sad")
        val idxNeutral = classLabels.indexOf("neutral")

        val aboveThresholds = mutableListOf<Pair<Int, Float>>()
        for (i in probs.indices) {
            val prob = probs[i]
            when (classLabels[i]) {
                "angry", "happy" -> if (prob > thresholdHappyAngry) aboveThresholds.add(i to prob)
                "fear", "disgust", "sad" -> if (prob > thresholdFearDisgustSad) aboveThresholds.add(i to prob)
                "neutral" -> if (prob > thresholdNeutral) aboveThresholds.add(i to prob)
            }
        }

        val now = SystemClock.elapsedRealtime()
        var candidateVibe: String

        if (aboveThresholds.isNotEmpty()) {
            // If only neutral is over threshold, show "neutral"
            if (aboveThresholds.size == 1 && aboveThresholds[0].first == idxNeutral) {
                candidateVibe = "neutral"
            } else {
                // Pick highest value (except "neutral" if others present)
                val (bestIdx, _) = aboveThresholds.maxByOrNull { it.second }!!
                if (bestIdx == idxNeutral && aboveThresholds.size > 1) {
                    val nonNeutral = aboveThresholds.filter { it.first != idxNeutral }
                    val (bestNonNeutralIdx, _) = nonNeutral.maxByOrNull { it.second }!!
                    candidateVibe = classLabels[bestNonNeutralIdx]
                } else {
                    candidateVibe = classLabels[bestIdx]
                }
            }
        } else {
            // No class passed the threshold: pick the one with the highest probability
            val maxIdx = probs.indices.maxByOrNull { probs[it] } ?: 0
            candidateVibe = classLabels[maxIdx]
        }

        // 0.5s delay logic for switching main vibe (except for neutral, which is immediate)
        if (candidateVibe != lastVibe && candidateVibe != "neutral") {
            if (lastSwitchTime == 0L || now - lastSwitchTime > 300) {
                lastVibe = candidateVibe
                lastSwitchTime = now
            } else {
                // Less than 0.5s since a new candidate: keep previous vibe
                // (do not update lastSwitchTime to allow the timer to continue)
                return lastVibe
            }
        } else {
            // If same or neutral, update immediately
            lastVibe = candidateVibe
            lastSwitchTime = now
        }

        return lastVibe
    }

    fun classifyEmotion(bitmap: Bitmap): String {
        return classifyEmotionWithThresholds(bitmap)
    }

    fun classifyEmotionWithProbs(bitmap: Bitmap): Pair<String, FloatArray> {
        val imageBuffer = preprocess(bitmap)
        val output = Array(1) { FloatArray(numClasses) }
        interpreter.run(imageBuffer, output)
        val predictedIdx = output[0].indices.maxByOrNull { output[0][it] } ?: 0
        return Pair(classLabels.getOrNull(predictedIdx) ?: classLabels[0], output[0])
    }

    fun getTop3Emotions(bitmap: Bitmap): List<Pair<String, Float>> {
        val imageBuffer = preprocess(bitmap)
        val output = Array(1) { FloatArray(numClasses) }
        interpreter.run(imageBuffer, output)
        val probs = output[0]
        return probs.mapIndexed { idx, prob -> classLabels[idx] to prob }
            .sortedByDescending { it.second }
            .take(3)
    }

    private fun preprocess(bitmap: Bitmap): ByteBuffer {
        val resized = Bitmap.createScaledBitmap(bitmap, inputImageWidth, inputImageHeight, true)
        val byteBuffer = ByteBuffer.allocateDirect(4 * inputImageWidth * inputImageHeight * inputChannels)
        byteBuffer.order(ByteOrder.nativeOrder())
        val pixels = IntArray(inputImageWidth * inputImageHeight)
        resized.getPixels(pixels, 0, inputImageWidth, 0, 0, inputImageWidth, inputImageHeight)
        for (i in pixels.indices) {
            val pixel = pixels[i]
            val r = android.graphics.Color.red(pixel)
            val g = android.graphics.Color.green(pixel)
            val b = android.graphics.Color.blue(pixel)
            val gray = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
            byteBuffer.putFloat(gray.toFloat())
        }
        byteBuffer.rewind()
        return byteBuffer
    }
}