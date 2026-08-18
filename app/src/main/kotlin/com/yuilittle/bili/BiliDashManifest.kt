package com.yuilittle.bili

import java.util.Locale

/** Builds the local static MPD used by Media3 for Bilibili DASH tracks. */
object BiliDashManifest {
    fun build(stream: BiliPlayUrl.DashStream): String {
        require(stream.isDash) { "DASH stream is required" }
        val duration = stream.durationMs.coerceAtLeast(1L)
        return buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            append(
                "<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" " +
                    "type=\"static\" profiles=\"urn:mpeg:dash:profile:isoff-on-demand:2011\" " +
                    "mediaPresentationDuration=\"${duration.toIsoDuration()}\" " +
                    "minBufferTime=\"PT1.500S\">\n"
            )
            append("  <Period start=\"PT0S\">\n")
            if (stream.videoTracks.isNotEmpty()) {
                append("    <AdaptationSet contentType=\"video\" mimeType=\"video/mp4\">\n")
                stream.videoTracks.forEach { appendVideo(it) }
                append("    </AdaptationSet>\n")
            }
            if (stream.audioTracks.isNotEmpty()) {
                append("    <AdaptationSet contentType=\"audio\" mimeType=\"audio/mp4\" lang=\"und\">\n")
                stream.audioTracks.forEach { appendAudio(it) }
                append("    </AdaptationSet>\n")
            }
            append("  </Period>\n</MPD>\n")
        }
    }

    private fun StringBuilder.appendVideo(track: BiliPlayUrl.DashVideoTrack) {
        append("      <Representation id=\"${track.id}\" bandwidth=\"${track.bandwidth.coerceAtLeast(1)}\"")
        append(" codecs=\"${track.codecs.escapeXml()}\"")
        if (track.width > 0) append(" width=\"${track.width}\"")
        if (track.height > 0) append(" height=\"${track.height}\"")
        if (track.frameRate.isNotBlank()) append(" frameRate=\"${track.frameRate.escapeXml()}\"")
        append(">\n")
        // Multiple <BaseURL> entries let ExoPlayer fail over to backup CDN hosts
        // automatically when the primary segment URL is unreachable.
        append("        <BaseURL>${track.url.escapeXml()}</BaseURL>\n")
        track.backups.take(2).forEach { backup ->
            append("        <BaseURL>${backup.escapeXml()}</BaseURL>\n")
        }
        appendSegmentBase(track.initializationRange, track.indexRange)
        append("      </Representation>\n")
    }

    private fun StringBuilder.appendAudio(track: BiliPlayUrl.DashAudioTrack) {
        append("      <Representation id=\"${track.id}\" bandwidth=\"${track.bandwidth.coerceAtLeast(1)}\"")
        append(" codecs=\"${track.codecs.escapeXml()}\">\n")
        append("        <BaseURL>${track.url.escapeXml()}</BaseURL>\n")
        track.backups.take(2).forEach { backup ->
            append("        <BaseURL>${backup.escapeXml()}</BaseURL>\n")
        }
        appendSegmentBase(track.initializationRange, track.indexRange)
        append("      </Representation>\n")
    }

    private fun StringBuilder.appendSegmentBase(initialization: String?, index: String?) {
        if (initialization.isNullOrBlank() && index.isNullOrBlank()) return
        append("        <SegmentBase")
        if (!index.isNullOrBlank()) append(" indexRange=\"${index.escapeXml()}\"")
        append(">\n")
        if (!initialization.isNullOrBlank()) {
            append("          <Initialization range=\"${initialization.escapeXml()}\"/>\n")
        }
        append("        </SegmentBase>\n")
    }

    private fun Long.toIsoDuration(): String = String.format(Locale.US, "PT%.3fS", this / 1000.0)

    private fun String.escapeXml(): String = this
        .replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
