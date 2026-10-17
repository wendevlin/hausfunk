package dev.wendelin.hausfunk.media

import org.webrtc.RTCStatsReport

/** What actually arrives, for the debug overlay: resolution, frame rate, data rate, codec. */
data class VideoStats(val width: Int, val height: Int, val fps: Double, val kbps: Int, val codec: String)

/** Turns successive WebRTC stats reports of one connection into [VideoStats]. */
class VideoStatsMeter {
    private var lastBytes = 0L
    private var lastUs = 0.0

    fun read(report: RTCStatsReport): VideoStats? {
        val stats = report.statsMap.values
        val inbound = stats.firstOrNull { it.type == "inbound-rtp" && it.members["kind"] == "video" } ?: return null
        val m = inbound.members
        val bytes = (m["bytesReceived"] as? Number)?.toLong() ?: 0L
        val kbps = if (lastUs > 0 && inbound.timestampUs > lastUs) {
            ((bytes - lastBytes) * 8 * 1000 / (inbound.timestampUs - lastUs)).toInt()
        } else {
            0
        }
        lastBytes = bytes
        lastUs = inbound.timestampUs
        val codec = stats.firstOrNull { it.id == m["codecId"] }?.members?.get("mimeType") as? String
        return VideoStats(
            width = (m["frameWidth"] as? Number)?.toInt() ?: 0,
            height = (m["frameHeight"] as? Number)?.toInt() ?: 0,
            fps = (m["framesPerSecond"] as? Number)?.toDouble() ?: 0.0,
            kbps = kbps,
            codec = codec?.substringAfter('/') ?: "?",
        )
    }
}
