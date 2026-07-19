package net.inkyquill.pocketeditor.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

class ReaderLayoutPolicyTest {
    @ParameterizedTest(name = "{0}dp x {1}dp uses {2}")
    @MethodSource("windowClasses")
    fun `window dimensions select stable reader layout`(
        widthDp: Int,
        heightDp: Int,
        expected: ReaderLayoutMode,
    ) {
        assertEquals(expected, ReaderLayoutPolicy.forWindow(widthDp, heightDp).mode)
    }

    @ParameterizedTest(name = "{0}dp window at {1}x font scale")
    @MethodSource("readableMeasures")
    fun `reader measure remains bounded and leaves touch-safe gutters`(
        widthDp: Int,
        fontScale: Float,
    ) {
        val policy = ReaderLayoutPolicy.forWindow(widthDp, if (widthDp < 600) 800 else 1280)

        assertTrue(policy.readerMaxWidthDp <= 720)
        assertTrue(policy.readerHorizontalPaddingDp >= 20)
        assertTrue(policy.minimumControlSizeDp >= 48)
        assertTrue(policy.proseLineHeightRatio in 1.45f..1.65f)
        assertTrue(policy.scaledProseSizeSp(fontScale) >= 17f * fontScale)
    }

    companion object {
        @JvmStatic
        fun windowClasses() = listOf(
            Arguments.of(360, 800, ReaderLayoutMode.PHONE),
            Arguments.of(599, 1000, ReaderLayoutMode.PHONE),
            Arguments.of(600, 960, ReaderLayoutMode.TABLET_PORTRAIT),
            Arguments.of(800, 1280, ReaderLayoutMode.TABLET_PORTRAIT),
            Arguments.of(960, 600, ReaderLayoutMode.TABLET_LANDSCAPE),
            Arguments.of(1280, 800, ReaderLayoutMode.TABLET_LANDSCAPE),
        )

        @JvmStatic
        fun readableMeasures() = listOf(
            Arguments.of(360, 1f),
            Arguments.of(800, 1.5f),
            Arguments.of(1280, 2f),
        )
    }
}
