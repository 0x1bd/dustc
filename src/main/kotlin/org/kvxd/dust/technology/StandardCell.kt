package org.kvxd.dust.technology

import org.kvxd.dust.device.geometry.BlockPos
import org.kvxd.dust.device.block.BlockState
import org.kvxd.dust.cell.definition.PortDirection
import org.kvxd.dust.cell.definition.CellType
import org.kvxd.dust.device.redstone.Redstone

class StandardCell internal constructor(
    val name: String,
    val logicalType: CellType,
    val size: CellSize,
    val pins: List<CellPin>,
    internal val blocks: List<Pair<BlockPos, BlockState>>,
    val implementation: CellImplementation = CellImplementation.Standard,
) {
    init {
        require(name.isNotBlank())
        validatePins(logicalType, size, pins)
        blocks.forEach { (position, _) ->
            require(position.x in 0 until size.x && position.y in 0 until size.y && position.z in 0 until size.z) {
                "$name contains a block outside its ${size.x}x${size.y}x${size.z} bounds at $position"
            }
        }
    }

    val latencyTicks: Int = logicalType.timing.arcs.maxOfOrNull {
        maxOf(it.rise.maxTicks, it.fall.maxTicks)
    } ?: 0

    fun pin(name: String): CellPin = pins.singleOrNull { it.name == name }
        ?: error("${this.name} has no pin '$name'")
}

private fun validatePins(logicalType: CellType, size: CellSize, pins: List<CellPin>) {
    require(pins.map { it.name }.distinct().size == pins.size) { "${logicalType.id} repeats a physical pin name" }
    require(pins.map { it.position }.distinct().size == pins.size) { "${logicalType.id} repeats a physical pin position" }

    val expected = logicalType.ports.flatMap { port ->
        List(port.width) { bit -> port.name to bit }
    }
    val actual = pins.map { it.port to it.bit }
    require(actual.size == expected.size && actual.toSet() == expected.toSet()) {
        val missing = expected.filter { it !in actual }.map(::formatPortBit)
        val extra = actual.filter { it !in expected }.map(::formatPortBit)
        "${logicalType.id} physical pins do not match its logical ports; missing=$missing, extra=$extra"
    }
    require(actual.distinct().size == actual.size) { "${logicalType.id} maps more than one physical pin to a logical port bit" }

    pins.forEach { pin ->
        val port = logicalType.port(pin.port)
        val expectedDirection = when (port.direction) {
            PortDirection.INPUT -> PinDirection.INPUT
            PortDirection.OUTPUT -> PinDirection.OUTPUT
        }
        require(pin.direction == expectedDirection) {
            "${logicalType.id} pin ${pin.name} is ${pin.direction}, but ${pin.port} is ${port.direction}"
        }
        require(pin.position.x in 0 until size.x && pin.position.y in 0 until size.y && pin.position.z in 0 until size.z) {
            "${logicalType.id} pin ${pin.name} is outside its ${size.x}x${size.y}x${size.z} bounds"
        }
        require(pin.position.z == size.z - 1) {
            "${logicalType.id} pin ${pin.name} is not on the routing edge"
        }
        require(pin.driveStrength in 1..Redstone.maximumSignalStrength) {
            "${logicalType.id} pin ${pin.name} has invalid drive strength ${pin.driveStrength}"
        }
        require(pin.requiredStrength in 1..Redstone.maximumSignalStrength) {
            "${logicalType.id} pin ${pin.name} has invalid required strength ${pin.requiredStrength}"
        }
    }
}

private fun formatPortBit(portBit: Pair<String, Int>): String = "${portBit.first}[${portBit.second}]"
