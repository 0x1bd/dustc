package org.kvxd.dust

data class CircuitIoGroup(
    val name: String?,
    val direction: CircuitPortDirection,
    val ports: List<CircuitPort>,
)
