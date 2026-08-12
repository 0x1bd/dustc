package org.kvxd.dust.physical.compilation

import org.kvxd.dust.device.geometry.BlockPos
import org.kvxd.dust.device.block.ComponentKind
import org.kvxd.dust.physical.design.PlacedCell
import org.kvxd.dust.technology.PinDirection
import org.kvxd.dust.technology.RedstoneTechnology

internal class CellRowPlacer(
    private val technology: RedstoneTechnology,
) {
    internal fun placeRowCells(
        row: Int,
        specs: List<CellSpec>,
        yOffset: Int = 0,
        xOffset: Int = 0,
    ): List<PlacedCell> {
        val rowDepth = specs.maxOf { it.cell.size.z }
        var nextX = xOffset
        return specs.mapIndexed { index, spec ->
            val placed = PlacedCell(
                spec.name,
                spec.cell,
                BlockPos(nextX, technology.cellOriginY + yOffset, rowDepth - spec.cell.size.z),
                row,
                spec.nets,
            )
            val next = specs.getOrNull(index + 1)
            nextX += spec.cell.size.x + if (next != null && canAbut(spec, next, rowDepth)) 0 else technology.cellGap
            placed
        }
    }

    internal fun canAbut(left: CellSpec, right: CellSpec, rowDepth: Int): Boolean {
        if (left.index < 0 || right.index < 0) return false
        val leftPins = left.cell.pins.filter { it.position.x == left.cell.size.x - 1 }
        val rightPins = right.cell.pins.filter { it.position.x == 0 }
        val joins = leftPins.flatMap { output ->
            if (output.direction != PinDirection.OUTPUT || !output.allowsHorizontalAbutment) return@flatMap emptyList()
            rightPins.mapNotNull { input ->
                if (input.direction != PinDirection.INPUT || !input.allowsHorizontalAbutment) return@mapNotNull null
                val leftSignal = left.nets.getValue(output.name)
                if (leftSignal != right.nets.getValue(input.name)) return@mapNotNull null
                val leftZ = rowDepth - left.cell.size.z + output.position.z
                val rightZ = rowDepth - right.cell.size.z + input.position.z
                if (output.position.y != input.position.y || leftZ != rightZ) return@mapNotNull null
                AbutmentSeam(output.position.y, leftZ, leftSignal)
            }
        }.associateBy { it.y to it.z }
        if (joins.isEmpty()) return false

        val leftBoundary = left.cell.blocks
            .filter { it.first.x == left.cell.size.x - 1 }
            .associate { (pos, state) ->
                (pos.y to (rowDepth - left.cell.size.z + pos.z)) to state.type.component
            }
        val rightBoundary = right.cell.blocks
            .filter { it.first.x == 0 }
            .associate { (pos, state) ->
                (pos.y to (rowDepth - right.cell.size.z + pos.z)) to state.type.component
            }
        val coordinates = leftBoundary.keys + rightBoundary.keys
        return coordinates.all { coordinate ->
            val leftComponent = leftBoundary[coordinate] ?: ComponentKind.NONE
            val rightComponent = rightBoundary[coordinate] ?: ComponentKind.NONE
            val leftElectrical = leftComponent != ComponentKind.NONE && leftComponent != ComponentKind.SUBSTRATE
            val rightElectrical = rightComponent != ComponentKind.NONE && rightComponent != ComponentKind.SUBSTRATE
            if (!leftElectrical && !rightElectrical) return@all true
            if (leftComponent == ComponentKind.NONE || rightComponent == ComponentKind.NONE) return@all true
            if (!leftElectrical || !rightElectrical) return@all false
            val join = joins[coordinate] ?: return@all false
            leftComponent == ComponentKind.WIRE && rightComponent == ComponentKind.WIRE && join.signal in left.nets.values
        }
    }

}
