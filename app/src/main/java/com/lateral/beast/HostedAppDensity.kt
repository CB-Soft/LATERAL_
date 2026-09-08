package com.lateral.beast

import kotlin.math.roundToInt

/** Density policy for apps rendered inside Beast cards. */
internal object HostedAppDensity {
    private const val PRESENTATION_SCALE = .78f
    const val RENDER_SCALE = 1f / PRESENTATION_SCALE
    private const val MIN_DPI = 72
    private const val MAX_DPI = 640

    /**
     * Preserve the phone's physical-to-logical relationship at any source DPI or
     * target resolution. TaskSurfaceView independently renders a larger buffer and
     * downsamples it, which also handles apps that cache pixel-sized typography.
     */
    fun calculate(phoneDensityDpi: Int, renderedHeightPx: Float, phoneWindowHeightPx: Int): Int {
        if (renderedHeightPx <= 0f || phoneWindowHeightPx <= 0) return MIN_DPI
        return (phoneDensityDpi * renderedHeightPx / phoneWindowHeightPx)
            .roundToInt()
            .coerceIn(MIN_DPI, MAX_DPI)
    }
}
