package com.lateral.beast

import org.junit.Assert.assertEquals
import org.junit.Test

class HostedAppDensityTest {
    @Test
    fun `render scale is the inverse of compact presentation scale`() {
        assertEquals(1f, HostedAppDensity.renderScale(1f, 1f) * .78f, .0001f)
    }

    @Test
    fun `user scale adjusts presentation consistently`() {
        assertEquals(1f, HostedAppDensity.renderScale(1.25f, 1f) * .78f * 1.25f, .0001f)
        assertEquals(1f, HostedAppDensity.renderScale(.75f, 1f) * .78f * .75f, .0001f)
    }

    @Test
    fun `supersampling preserves effective dp scale`() {
        val baseDensity = 165
        val nativeRatio = baseDensity / HostedAppDensity.renderScale(1f, 1f)
        val highRatio = HostedAppDensity.supersampledDensity(baseDensity, 1.25f) /
            HostedAppDensity.renderScale(1f, 1.25f)

        assertEquals(nativeRatio, highRatio, .5f)
    }

    @Test
    fun `emulator phone geometry preserves logical size`() {
        assertEquals(165, HostedAppDensity.calculate(420, 1058f, 2688))
    }

    @Test
    fun `same-height surface keeps source density`() {
        assertEquals(240, HostedAppDensity.calculate(240, 1000f, 1000))
    }

    @Test
    fun `equivalent logical phones produce the same hosted density`() {
        val fullHdPhone = HostedAppDensity.calculate(420, 1058f, 2400)
        val quadHdPhone = HostedAppDensity.calculate(560, 1058f, 3200)

        assertEquals(fullHdPhone, quadHdPhone)
        assertEquals(185, fullHdPhone)
    }

    @Test
    fun `hosted density scales with arbitrary rendered resolution`() {
        val smallCard = HostedAppDensity.calculate(420, 800f, 2400)
        val doubleResolutionCard = HostedAppDensity.calculate(420, 1600f, 2400)

        assertEquals(140, smallCard)
        assertEquals(280, doubleResolutionCard)
    }

    @Test
    fun `density remains inside safe virtual-display bounds`() {
        assertEquals(72, HostedAppDensity.calculate(120, 100f, 4000))
        assertEquals(640, HostedAppDensity.calculate(640, 4000f, 100))
    }
}
