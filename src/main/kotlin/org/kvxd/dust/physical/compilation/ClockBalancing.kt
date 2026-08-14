package org.kvxd.dust.physical.compilation

import org.kvxd.dust.cell.behavior.CellBehavior
import org.kvxd.dust.device.geometry.BlockPos
import org.kvxd.dust.physical.design.PlacedCell

internal fun clockPadding(cells: List<PlacedCell>, delays: Map<BlockPos, Int>): Map<BlockPos, Int> = buildMap {
    val sinks = cells.flatMap { cell ->
        val trigger = (cell.cell.logicalType.behavior as? CellBehavior.Stateful)?.trigger
            as? CellBehavior.Trigger.EdgeTriggered
        if (trigger == null) {
            emptyList()
        } else {
            cell.cell.pins.filter { it.port == trigger.clockPort }.map { pin ->
                cell.nets.getValue(pin.name) to cell.pin(pin.name)
            }
        }
    }
    sinks.groupBy { it.first }.values.forEach { clockSinks ->
        val latest = clockSinks.maxOf { (_, position) -> delays[position] ?: 0 }
        clockSinks.forEach { (_, position) -> put(position, latest - (delays[position] ?: 0)) }
    }
}

internal fun balancedClockDelays(
    cells: List<PlacedCell>,
    delays: Map<BlockPos, Int>,
): Map<BlockPos, Int> = delays.toMutableMap().apply {
    clockPadding(cells, delays).forEach { (position, padding) ->
        this[position] = (this[position] ?: 0) + padding
    }
}
