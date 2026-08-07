package org.kvxd.dust.netlist

import org.kvxd.dust.cell.CellType

data class CellInstance(
    val name: String,
    val type: CellType,
    val connections: Map<String, List<Signal>>,
    val primitive: Primitive? = null,
) {
    init {
        require(name.isNotBlank())
        require(connections.keys == type.ports.map { it.name }.toSet()) { "$name does not connect every ${type.id} port" }
        type.ports.forEach { port ->
            require(connections.getValue(port.name).size == port.width) {
                "$name port ${port.name} needs ${port.width} bits"
            }
        }
    }
}
