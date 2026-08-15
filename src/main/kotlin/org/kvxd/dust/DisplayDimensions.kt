package org.kvxd.dust

data class DisplayDimensions(val width: Int, val height: Int) {
    init {
        require(width > 1) { "display width must be greater than one" }
        require(height > 1) { "display height must be greater than one" }
    }

    val xWidth: Int = Int.SIZE_BITS - Integer.numberOfLeadingZeros(width - 1)
    val yWidth: Int = Int.SIZE_BITS - Integer.numberOfLeadingZeros(height - 1)
    val inputWidth: Int = xWidth + yWidth + CONTROL_BITS

    private companion object {
        const val CONTROL_BITS: Int = 3
    }
}
