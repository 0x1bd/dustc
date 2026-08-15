package org.kvxd.dust

data class DisplayDimensions(val width: Int, val height: Int) {
    init {
        require(width in 8..64 && width % 2 == 0) { "display width must be even and between 8 and 64" }
        require(height in 8..64 && height % 2 == 0) { "display height must be even and between 8 and 64" }
    }
}
