package org.kvxd.dust

import org.kvxd.dust.lang.MAX_BUS_WIDTH
import org.kvxd.dust.physical.io.PhysicalIoEdge

data class CircuitPort(
    val name: String,
    val width: Int,
    val direction: CircuitPortDirection,
    val ioGroup: String? = null,
    val edge: PhysicalIoEdge? = null,
    val panel: Boolean = false,
    val display: DisplayDimensions? = null,
) {
    init {
        require(name.matches(PORT_NAME)) { "invalid port '$name'" }
        require(width in 1..MAX_BUS_WIDTH) { "$name has invalid width $width" }
        require(ioGroup == null || ioGroup.matches(PORT_NAME)) { "invalid I/O group '$ioGroup'" }
        require(!panel || ioGroup != null) { "#[panel] requires a named I/O group" }
        require(display == null || direction == CircuitPortDirection.OUTPUT) { "a display must be an output" }
        require(display == null || !panel) { "a display output cannot be a panel" }
    }

    internal fun bitName(bit: Int): String = if (width == 1) name else "$name[$bit]"
}
