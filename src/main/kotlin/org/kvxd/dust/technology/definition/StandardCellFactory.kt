package org.kvxd.dust.technology.definition

import org.kvxd.dust.cell.definition.CellType
import org.kvxd.dust.device.block.BlockState
import org.kvxd.dust.device.block.BlockEntity
import org.kvxd.dust.device.geometry.BlockPos
import org.kvxd.dust.technology.CellImplementation
import org.kvxd.dust.technology.CellObservation
import org.kvxd.dust.technology.CellPin
import org.kvxd.dust.technology.CellSize
import org.kvxd.dust.technology.StandardCell

internal class StandardCellFactory(
    private val templates: Map<String, BlockState>,
    private val include: (String, List<Int>) -> StandardCell,
) {
    constructor(templates: Map<String, BlockState>, include: (String) -> StandardCell) :
        this(templates, { name, arguments ->
            require(arguments.isEmpty()) { "$name does not accept parameters in this factory" }
            include(name)
        })

    fun create(
        definition: CellDefinition,
        logicalType: CellType,
        implementation: CellImplementation = CellImplementation.Standard,
    ): StandardCell {
        val symbols = definition.palette.associate { entry ->
            entry.symbol to resolveState(entry.template, definition.name)
        }
        val blocks = linkedMapOf<BlockPos, BlockState>()
        val blockEntities = linkedMapOf<BlockPos, BlockEntity>()
        var maximum = BlockPos(-1, -1, -1)

        fun extend(position: BlockPos) {
            maximum = BlockPos(maxOf(maximum.x, position.x), maxOf(maximum.y, position.y), maxOf(maximum.z, position.z))
        }

        fun fail(key: String?, message: String): Nothing {
            key?.let(definition.sourceLocations::get)?.error(message)
            definition.sourceLocations["definition"]?.error(message)
            throw IllegalArgumentException(message)
        }

        fun put(position: BlockPos, state: BlockState, sourceKey: String? = null) {
            val previous = blocks.putIfAbsent(position, state)
            if (previous != null && previous != state) {
                fail(sourceKey, "${definition.name} overlaps incompatible blocks at $position")
            }
            extend(position)
        }

        definition.layers.forEach { layer ->
            layer.rows.forEachIndexed { z, row ->
                row.forEachIndexed { x, symbol -> if (symbol != '.') put(BlockPos(x, layer.y, z), symbols.getValue(symbol)) }
                extend(BlockPos(row.lastIndex, layer.y, z))
            }
        }
        definition.layout.forEachIndexed { index, entry ->
            val sourceKey = "layout:$index"
            when (entry) {
                is CellLayoutEntry.Include -> {
                    val cell = include(entry.cell, entry.arguments)
                    extend(entry.origin + BlockPos(cell.size.x - 1, cell.size.y - 1, cell.size.z - 1))
                    cell.blocks.forEach { (position, state) -> put(entry.origin + position, state, sourceKey) }
                    cell.blockEntities.forEach { (position, entity) ->
                        val absolute = entry.origin + position
                        if (blockEntities.putIfAbsent(absolute, entity) != null) {
                            fail(sourceKey, "${definition.name} overlaps block entities at $absolute")
                        }
                    }
                }
                is CellLayoutEntry.Wire -> {
                    val support = entry.position + BlockPos(0, -1, 0)
                    blocks[support]?.let {
                        require(it.type.isSolid) { "${definition.name} wire at ${entry.position} lacks solid support" }
                    } ?: put(support, templates.getValue("support"), sourceKey)
                    put(entry.position, resolveState(entry.template, definition.name), sourceKey)
                }
                is CellLayoutEntry.Block -> put(entry.position, symbols.getValue(entry.paletteSymbol), sourceKey)
            }
        }

        definition.blockEntities.forEachIndexed { index, entry ->
            if (blockEntities.putIfAbsent(entry.position, entry.blockEntity) != null) {
                fail("block-entity:$index", "${definition.name} overlaps block entities at ${entry.position}")
            }
            extend(entry.position)
        }
        CellDefinitionValidator.validateBlockEntities(definition, blocks)

        val pins = definition.pins.map { pin ->
            extend(pin.position)
            CellPin(
                pin.name,
                pin.direction,
                pin.position,
                allowsHorizontalAbutment = pin.allowsHorizontalAbutment,
                accessesFromSouth = pin.accessesFromSouth,
                driveStrength = pin.driveStrength,
                requiredStrength = pin.requiredStrength,
            )
        }
        val size = CellSize(maximum.x + 1, maximum.y + 1, maximum.z + 1)
        pins.forEach { pin ->
            require(pin.position.z == size.z - 1) { "${definition.name} pin ${pin.name} is not on the routing edge" }
        }
        val selectedImplementation = definition.placement?.let {
            CellImplementation.HardMacro(it.exclusiveRow, it.visibleEdge)
        } ?: implementation
        return runCatching {
            StandardCell(
                definition.name,
                logicalType,
                size,
                pins,
                blocks.entries.map { it.key to it.value },
                selectedImplementation,
                blockEntities,
                definition.observations.map { CellObservation(it.name, it.position) },
                definition.timing ?: logicalType.timing,
            )
        }.getOrElse { exception -> fail("definition", exception.message ?: "invalid standard cell") }
    }

    private fun resolveState(text: String, cell: String): BlockState = templates[text]
        ?: BlockState.parseOrNull(text)
        ?: throw IllegalArgumentException("$cell references unknown block state '$text'")
}
