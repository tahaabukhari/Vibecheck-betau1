package com.example.vibecheck

import android.content.Context
import android.widget.Toast

class AutoSnapManager(
    private val context: Context,
    private val cooldownMs: Long = 5000L
) {
    private var lastAutoSnapTime: Long = 0L
    @Volatile
    private var isAutoSnapping = false
    @Volatile
    private var isCapturingPhoto = false

    fun shouldAutoSnap(vibes: List<String>): Boolean {
        val now = System.currentTimeMillis()
        val filtered = vibes.filter { it != "Unknown" && it != "neutral" }
        if (filtered.size > 2 && filtered.all { it == filtered.first() }) {
            if (!isAutoSnapping && !isCapturingPhoto && (now - lastAutoSnapTime > cooldownMs)) {
                isAutoSnapping = true
                lastAutoSnapTime = now
                return true
            }
        }
        return false
    }

    fun onSnapStarted() {
        isCapturingPhoto = true
    }

    fun onSnapCompleted() {
        isAutoSnapping = false
        isCapturingPhoto = false
        Toast.makeText(context, "Vibe match! Auto-captured photo.", Toast.LENGTH_SHORT).show()
    }

    fun onSnapFailed(message: String) {
        isAutoSnapping = false
        isCapturingPhoto = false
        Toast.makeText(context, "AutoSnap failed: $message", Toast.LENGTH_SHORT).show()
    }
}