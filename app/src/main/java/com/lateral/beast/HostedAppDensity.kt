package com.lateral.beast

import kotlin.math.roundToInt

/** Density policy for apps rendered inside Beast cards. */
internal object HostedAppDensity {
    private const val DEFAULT_PRESENTATION_SCALE = .78f
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

    fun renderScale(userScale: Float, supersampling: Float): Float =
        supersampling.coerceIn(1f, 2f) /
            (DEFAULT_PRESENTATION_SCALE * userScale.coerceIn(.6f, 1.4f))

    fun supersampledDensity(densityDpi: Int, supersampling: Float): Int =
        (densityDpi * supersampling.coerceIn(1f, 2f)).roundToInt().coerceIn(MIN_DPI, MAX_DPI)
}
