package org.kvxd.dust.cell

class CellType(
    val id: CellTypeId,
    val ports: List<CellPort>,
    val behavior: CellBehavior,
    val timing: CellTiming,
) {
    init {
        require(ports.isNotEmpty()) { "$id has no ports" }
        require(ports.map { it.name }.distinct().size == ports.size) { "$id has duplicate ports" }
        val byName = ports.associateBy { it.name }
        timing.arcs.forEach { arc ->
            require(arc.fromPort in byName && arc.toPort in byName) { "$id has an arc for an unknown port" }
            val from = byName.getValue(arc.fromPort)
            val to = byName.getValue(arc.toPort)
            require(arc.fromBit == null || arc.fromBit in 0 until from.width) {
                "$id timing arc starts outside ${from.name}[${from.width}]"
            }
            require(arc.toBit == null || arc.toBit in 0 until to.width) {
                "$id timing arc ends outside ${to.name}[${to.width}]"
            }
            require(from.direction != PortDirection.OUTPUT && to.direction != PortDirection.INPUT) {
                "$id timing arc ${arc.fromPort} -> ${arc.toPort} has incompatible directions"
            }
        }
        timing.constraints.forEach { constraint ->
            when (constraint) {
                is TimingConstraint.SetupHold -> {
                    requireInputPort(byName, constraint.dataPort, id)
                    requireInputPort(byName, constraint.clockPort, id)
                }
            }
        }
    }

    fun port(name: String): CellPort = ports.singleOrNull { it.name == name }
        ?: error("$id has no port '$name'")

}

private fun requireInputPort(ports: Map<String, CellPort>, name: String, id: CellTypeId) {
    val port = requireNotNull(ports[name]) { "$id constraint references unknown port '$name'" }
    require(port.direction == PortDirection.INPUT) {
        "$id constraint port '$name' is not an input"
    }
}
