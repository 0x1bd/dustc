package org.kvxd.dust.physical.io

data class PhysicalIoGroup(
    val name: String?,
    val direction: PhysicalIoDirection,
    val signals: List<String>,
    val edge: PhysicalIoEdge? = null,
    val panel: Boolean = false,
) {
    init {
        require(signals.isNotEmpty()) { "an I/O group cannot be empty" }
        require(signals.distinct().size == signals.size) { "I/O group '$name' repeats a signal" }
        require(!panel || name != null) { "a panel requires a named I/O group" }
        require(!panel || edge == null || edge == PhysicalIoEdge.NORTH || edge == PhysicalIoEdge.SOUTH) {
            "a panel currently supports north/south edges"
        }
    }
}
