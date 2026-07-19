package net.inkyquill.pocketeditor.ui

enum class ReaderLayoutMode {
    PHONE,
    TABLET_PORTRAIT,
    TABLET_LANDSCAPE,
}

data class ReaderLayoutPolicy(
    val mode: ReaderLayoutMode,
    val readerMaxWidthDp: Int = 720,
    val readerHorizontalPaddingDp: Int = 24,
    val minimumControlSizeDp: Int = 48,
    val proseLineHeightRatio: Float = 1.56f,
    val baseProseSizeSp: Float = 18f,
) {
    fun scaledProseSizeSp(fontScale: Float): Float = baseProseSizeSp * fontScale

    companion object {
        fun forWindow(widthDp: Int, heightDp: Int): ReaderLayoutPolicy {
            require(widthDp > 0 && heightDp > 0)
            val mode = when {
                widthDp < 600 -> ReaderLayoutMode.PHONE
                widthDp > heightDp -> ReaderLayoutMode.TABLET_LANDSCAPE
                else -> ReaderLayoutMode.TABLET_PORTRAIT
            }
            return ReaderLayoutPolicy(mode = mode)
        }
    }
}
