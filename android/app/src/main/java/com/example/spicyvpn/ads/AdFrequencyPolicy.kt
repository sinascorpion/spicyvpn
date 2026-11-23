package com.example.spicyvpn.ads

object AdFrequencyPolicy {
    const val WINDOW_MS = 24L * 60 * 60 * 1000
    const val COOLDOWN_MS = 4L * 60 * 60 * 1000
    fun active(now: Long, impressions: List<Long>): List<Long> = impressions.filter { it > now - WINDOW_MS }
    fun canShow(now: Long, impressions: List<Long>): Boolean {
        val recent = active(now, impressions)
        return recent.size < 3 && (recent.maxOrNull()?.let { now - it >= COOLDOWN_MS } ?: true)
    }
}
