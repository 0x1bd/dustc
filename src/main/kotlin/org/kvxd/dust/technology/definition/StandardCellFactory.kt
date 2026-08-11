package org.kvxd.dust.technology.definition

import org.kvxd.dust.cell.definition.CellTypeId
import org.kvxd.dust.cell.library.BuiltinCells
import org.kvxd.dust.device.block.BlockState
import org.kvxd.dust.device.geometry.BlockPos
import org.kvxd.dust.technology.CellPin
import org.kvxd.dust.technology.CellSize
import org.kvxd.dust.technology.StandardCell

internal class StandardCellFactory(
    private val templates: Map<String, BlockState>,
    private val include: (String) -> StandardCell,
) {
    fun create(definition: CellDefinition): StandardCell {
        val symbols = definition.palette.associate { it.symbol to templates.getValue(it.template) }
        val blocks = linkedMapOf<BlockPos, BlockState>()
        var maximum = BlockPos(-1, -1, -1)

        fun extend(position: BlockPos) {
            maximum = BlockPos(maxOf(maximum.x, position.x), maxOf(maximum.y, position.y), maxOf(maximum.z, position.z))
        }

        fun put(position: BlockPos, state: BlockState) {
            val previous = blocks.putIfAbsent(position, state)
            require(previous == null || previous == state) {
                "${definition.name} overlaps incompatible blocks at $position"
            }
            extend(position)
        }

        definition.layers.forEach { layer ->
            layer.rows.forEachIndexed { z, row ->
                row.forEachIndexed { x, symbol -> if (symbol != '.') put(BlockPos(x, layer.y, z), symbols.getValue(symbol)) }
                extend(BlockPos(row.lastIndex, layer.y, z))
            }
        }
        definition.layout.forEach { entry ->
            when (entry) {
                is CellLayoutEntry.Include -> {
                    val cell = include(entry.cell)
                    extend(entry.origin + BlockPos(cell.size.x - 1, cell.size.y - 1, cell.size.z - 1))
                    cell.blocks.forEach { (position, state) -> put(entry.origin + position, state) }
                }
                is CellLayoutEntry.Wire -> {
                    val support = entry.position + BlockPos(0, -1, 0)
                    blocks[support]?.let {
                        require(it.type.isSolid) { "${definition.name} wire at ${entry.position} lacks solid support" }
                    } ?: put(support, templates.getValue("support"))
                    put(entry.position, templates.getValue(entry.template))
                }
            }
        }

        val pins = definition.pins.map { pin ->
            extend(pin.position)
            CellPin(
                pin.name,
                pin.direction,
                pin.position,
                allowsHorizontalAbutment = pin.allowsHorizontalAbutment,
                accessesFromSouth = pin.accessesFromSouth,
                branchOffsetX = pin.branchOffsetX,
                driveStrength = pin.driveStrength,
                requiredStrength = pin.requiredStrength,
            )
        }
        val size = CellSize(maximum.x + 1, maximum.y + 1, maximum.z + 1)
        pins.forEach { pin ->
            require(pin.position.z == size.z - 1) { "${definition.name} pin ${pin.name} is not on the routing edge" }
        }
        return StandardCell(
            definition.name,
            BuiltinCells.byId.getValue(CellTypeId(definition.name)),
            size,
            pins,
            blocks.entries.map { it.key to it.value },
        )
    }
}
