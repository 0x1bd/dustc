package org.kvxd.dust.cli

import java.io.PrintWriter
import kotlin.math.roundToInt
import org.kvxd.dust.physical.PhysicalProgressEvent
import org.kvxd.dust.physical.PhysicalProgressListener
import org.kvxd.dust.physical.PhysicalProgressStage

internal class CliProgressRenderer(
    private val out: PrintWriter,
    private val interactive: Boolean = System.console() != null,
) : PhysicalProgressListener {
    private var stage: PhysicalProgressStage? = null
    private var stageStartedNanos: Long = System.nanoTime()
    private var lastRenderedNanos: Long = 0
    private var lastWidth = 0
    private var finished = false

    override fun onProgress(event: PhysicalProgressEvent) {
        val now = System.nanoTime()
        val stageChanged = event.stage != stage
        if (stageChanged) {
            stage = event.stage
            stageStartedNanos = now
            if (!interactive) out.println("dustc: ${stageName(event.stage)}")
        }
        if (!interactive) return

        val completed = event.completed
        val total = event.total
        val isCompletion = completed != null && total != null && completed >= total
        if (!stageChanged && !isCompletion && now - lastRenderedNanos < MIN_RENDER_INTERVAL_NANOS) return
        render(event, now)
    }

    fun finish() {
        if (finished) return
        finished = true
        if (interactive && lastWidth > 0) {
            out.println()
            out.flush()
        }
    }

    private fun render(event: PhysicalProgressEvent, now: Long) {
        val fraction = progressFraction(event)
        val bar = progressBar(fraction)
        val percent = fraction?.let { " ${(it * 100).roundToInt().coerceIn(0, 100)}%" }.orEmpty()
        val detail = buildList {
            if (event.candidate != null && event.candidateTotal != null) {
                add("candidate ${event.candidate}/${event.candidateTotal}")
            }
            if (event.net != null && event.netTotal != null) add("net ${event.net}/${event.netTotal}")
            if (event.negotiationPass != null) add("pass ${event.negotiationPass}")
            if (event.conflicts != null) add("${event.conflicts} conflicts")
            event.detail?.takeIf { it.isNotBlank() }?.let(::add)
            approximateEta(event, fraction, now)?.let { add("ETA ~$it") }
        }.joinToString(" · ")
        val line = buildString {
            append('[').append(bar).append("] ").append(stageName(event.stage)).append(percent)
            if (detail.isNotEmpty()) append("  ").append(detail)
        }
        out.print('\r')
        out.print(line.padEnd(lastWidth))
        out.flush()
        lastWidth = maxOf(lastWidth, line.length)
        lastRenderedNanos = now
    }

    private fun progressFraction(event: PhysicalProgressEvent): Double? {
        val completed = event.completed ?: return null
        val total = event.total ?: return null
        if (total <= 0) return null
        var work = completed.toDouble()
        val net = event.net
        val netTotal = event.netTotal
        if (event.stage == PhysicalProgressStage.ROUTING && completed < total &&
            net != null && netTotal != null && netTotal > 0
        ) {
            work += net.toDouble() / netTotal
        }
        return (work / total).coerceIn(0.0, 1.0)
    }

    private fun approximateEta(event: PhysicalProgressEvent, fraction: Double?, now: Long): String? {
        if (!event.approximate || fraction == null || fraction <= 0.05 || fraction >= 1.0) return null
        val elapsedSeconds = (now - stageStartedNanos) / 1_000_000_000.0
        if (elapsedSeconds < 1.0) return null
        val remaining = elapsedSeconds * (1.0 - fraction) / fraction
        return when {
            remaining < 1.0 -> "1s"
            remaining < 90.0 -> "${remaining.roundToInt()}s"
            else -> "${(remaining / 60.0).roundToInt()}m"
        }
    }

    private fun progressBar(fraction: Double?): String {
        if (fraction == null) return "·".repeat(BAR_WIDTH)
        val filled = (fraction * BAR_WIDTH).roundToInt().coerceIn(0, BAR_WIDTH)
        return "█".repeat(filled) + "░".repeat(BAR_WIDTH - filled)
    }

    private fun stageName(stage: PhysicalProgressStage): String = stage.name.lowercase().replace('_', ' ')

    private companion object {
        const val BAR_WIDTH = 20
        const val MIN_RENDER_INTERVAL_NANOS = 100_000_000L
    }
}
