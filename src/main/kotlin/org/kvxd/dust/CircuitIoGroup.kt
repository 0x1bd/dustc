package org.kvxd.dust

import org.kvxd.dust.physical.io.PhysicalIoEdge

data class CircuitIoGroup(
    val name: String?,
    val direction: CircuitPortDirection,
    val ports: List<CircuitPort>,
    val edge: PhysicalIoEdge? = null,
    val panel: Boolean = false,
)
