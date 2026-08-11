package org.kvxd.dust.technology

import org.kvxd.dust.device.geometry.BlockPos
import org.kvxd.dust.device.block.BlockState
import org.kvxd.dust.cell.definition.CellType

class StandardCell internal constructor(
    val name: String,
    val logicalType: CellType,
    val size: CellSize,
    val pins: List<CellPin>,
    internal val blocks: List<Pair<BlockPos, BlockState>>,
) {
    init {
        validatePins(logicalType, pins)
    }

    val latencyTicks: Int = logicalType.timing.arcs.maxOfOrNull {
        maxOf(it.rise.maxTicks, it.fall.maxTicks)
    } ?: 0

    fun pin(name: String): CellPin = pins.singleOrNull { it.name == name }
        ?: error("${this.name} has no pin '$name'")
}

private fun validatePins(logicalType: CellType, pins: List<CellPin>) {
    require(pins.map { it.name }.distinct().size == pins.size)
    pins.forEach { pin ->
        val port = logicalType.port(pin.port)
        require(pin.bit in 0 until port.width) {
            "${logicalType.id} pin ${pin.name} maps outside ${pin.port}[${port.width}]"
        }
    }
}
