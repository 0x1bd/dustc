package org.kvxd.dust.technology

import org.kvxd.dust.device.geometry.BlockPos
import org.kvxd.dust.device.block.BlockEntity
import org.kvxd.dust.device.block.BlockState
import org.kvxd.dust.cell.definition.PortDirection
import org.kvxd.dust.cell.definition.CellType
import org.kvxd.dust.cell.timing.CellTiming
import org.kvxd.dust.cell.timing.TimingConstraint
import org.kvxd.dust.device.redstone.Redstone

class StandardCell internal constructor(
    val name: String,
    val logicalType: CellType,
    val size: CellSize,
    val pins: List<CellPin>,
    internal val blocks: List<Pair<BlockPos, BlockState>>,
    val implementation: CellImplementation = CellImplementation.Standard,
    internal val blockEntities: Map<BlockPos, BlockEntity> = emptyMap(),
    val observations: List<CellObservation> = emptyList(),
    val timing: CellTiming = logicalType.timing,
) {
    init {
        require(name.isNotBlank())
        validatePins(logicalType, size, pins)
        blocks.forEach { (position, _) ->
            require(position.x in 0 until size.x && position.y in 0 until size.y && position.z in 0 until size.z) {
                "$name contains a block outside its ${size.x}x${size.y}x${size.z} bounds at $position"
            }
        }
        require(blocks.map { it.first }.distinct().size == blocks.size) { "$name repeats a block position" }
        blockEntities.forEach { (position, _) ->
            require(position.x in 0 until size.x && position.y in 0 until size.y && position.z in 0 until size.z) {
                "$name contains a block entity outside its ${size.x}x${size.y}x${size.z} bounds at $position"
            }
            require(blocks.any { it.first == position }) { "$name has a block entity without a block at $position" }
        }
        require(observations.map { it.name }.distinct().size == observations.size) { "$name repeats an observation name" }
        require(observations.map { it.position }.distinct().size == observations.size) {
            "$name repeats an observation position"
        }
        observations.forEach { observation ->
            require(
                observation.position.x in 0 until size.x && observation.position.y in 0 until size.y &&
                    observation.position.z in 0 until size.z,
            ) { "$name observation ${observation.name} is outside its bounds at ${observation.position}" }
        }
        validateTiming(logicalType, timing)
    }

    val latencyTicks: Int = timing.arcs.maxOfOrNull {
        maxOf(it.rise.maxTicks, it.fall.maxTicks)
    } ?: 0

    fun pin(name: String): CellPin = pins.singleOrNull { it.name == name }
        ?: error("${this.name} has no pin '$name'")
}

private fun validateTiming(logicalType: CellType, timing: CellTiming) {
    val ports = logicalType.ports.associateBy { it.name }
    timing.arcs.forEach { arc ->
        val from = requireNotNull(ports[arc.fromPort]) { "${logicalType.id} timing references unknown port '${arc.fromPort}'" }
        val to = requireNotNull(ports[arc.toPort]) { "${logicalType.id} timing references unknown port '${arc.toPort}'" }
        require(from.direction == PortDirection.INPUT && to.direction == PortDirection.OUTPUT) {
            "${logicalType.id} timing arc ${arc.fromPort} -> ${arc.toPort} has incompatible directions"
        }
        require(arc.fromBit == null || arc.fromBit in 0 until from.width)
        require(arc.toBit == null || arc.toBit in 0 until to.width)
    }
    timing.constraints.forEach { constraint ->
        when (constraint) {
            is TimingConstraint.SetupHold -> {
                val data = requireNotNull(ports[constraint.dataPort]) {
                    "${logicalType.id} timing references unknown port '${constraint.dataPort}'"
                }
                val clock = requireNotNull(ports[constraint.clockPort]) {
                    "${logicalType.id} timing references unknown port '${constraint.clockPort}'"
                }
                require(data.direction == PortDirection.INPUT && clock.direction == PortDirection.INPUT) {
                    "${logicalType.id} setup/hold constraint ports must be inputs"
                }
            }
        }
    }
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
