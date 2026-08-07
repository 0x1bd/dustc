package org.kvxd.dust

data class CircuitPort(
    val name: String,
    val width: Int,
    val direction: CircuitPortDirection,
    val ioGroup: String? = null,
) {
    init {
        require(name.matches(PORT_NAME)) { "invalid port '$name'" }
        require(width in 1..ULong.SIZE_BITS) { "$name has invalid width $width" }
        require(ioGroup == null || ioGroup.matches(PORT_NAME)) { "invalid I/O group '$ioGroup'" }
    }

    internal fun bitName(bit: Int): String = if (width == 1) name else "$name[$bit]"
}
