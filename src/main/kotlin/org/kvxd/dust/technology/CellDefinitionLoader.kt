package org.kvxd.dust.technology

import org.kvxd.dust.cell.definition.CellTypeId
import org.kvxd.dust.cell.library.BuiltinCells
import org.kvxd.dust.device.block.BlockState
import org.kvxd.dust.device.geometry.BlockPos

internal class CellDefinitionLoader(private val palette: Map<String, BlockState>) {
    private val cells = mutableMapOf<String, StandardCell>()

    fun load(name: String): StandardCell = cells.getOrPut(name) { parse(name) }

    private fun parse(name: String): StandardCell {
        val lines = requireNotNull(javaClass.getResourceAsStream("/org/kvxd/dust/technology/cells/$name.txt")) {
            "missing cell definition for $name"
        }.bufferedReader().useLines { sequence ->
            sequence.map(String::trim).filter { line -> line.isNotEmpty() && !line.startsWith("//") }.toList()
        }
        require(lines.firstOrNull() == "cell $name") { "cell definition name mismatch for $name" }

        val pins = mutableListOf<CellPin>()
        val symbols = mutableMapOf<Char, BlockState>()
        val blocks = linkedMapOf<BlockPos, BlockState>()
        var maxX = -1
        var maxY = -1
        var maxZ = -1
        var section = ""
        var layerY = -1
        var layerZ = 0

        fun extend(position: BlockPos) {
            maxX = maxOf(maxX, position.x)
            maxY = maxOf(maxY, position.y)
            maxZ = maxOf(maxZ, position.z)
        }

        fun put(position: BlockPos, state: BlockState) {
            val previous = blocks.putIfAbsent(position, state)
            require(previous == null || previous == state) { "$name overlaps incompatible blocks at $position" }
            extend(position)
        }

        fun position(text: String): BlockPos {
            val values = text.removePrefix("@").split(',').map(String::toInt)
            require(values.size == 3) { "invalid position '$text' in $name" }
            return BlockPos(values[0], values[1], values[2])
        }

        lines.drop(1).forEach { line ->
            when (line) {
                "palette:", "pins:", "layers:", "layout:" -> section = line.dropLast(1)
                else -> when (section) {
                    "palette" -> {
                        val (symbol, template) = line.split(" = ", limit = 2)
                        symbols[symbol.single()] = palette.getValue(template)
                    }
                    "pins" -> {
                        val parts = line.split(Regex("\\s+"))
                        val options = parts.drop(3).associate { option -> option.substringBefore('=') to option.substringAfter('=') }
                        val pinPosition = position(parts[2])
                        pins += CellPin(
                            name = parts[0],
                            direction = PinDirection.valueOf(parts[1].uppercase()),
                            position = pinPosition,
                            allowsHorizontalAbutment = options["abut"]?.toBoolean() ?: true,
                            accessesFromSouth = options["south"]?.toBoolean() ?: false,
                            branchOffsetX = options["branch"]?.toInt() ?: 0,
                            driveStrength = options["drive"]?.toInt() ?: 15,
                            requiredStrength = options["required"]?.toInt() ?: 1,
                        )
                        extend(pinPosition)
                    }
                    "layers" -> if (line.startsWith('@')) {
                        layerY = line.drop(1).toInt()
                        layerZ = 0
                    } else {
                        require(layerY >= 0) { "layer row without a y coordinate in $name" }
                        line.forEachIndexed { x, symbol -> if (symbol != '.') put(BlockPos(x, layerY, layerZ), symbols.getValue(symbol)) }
                        extend(BlockPos(line.lastIndex, layerY, layerZ))
                        layerZ++
                    }
                    "layout" -> {
                        val parts = line.split(Regex("\\s+"))
                        when (parts[0]) {
                            "include" -> {
                                val origin = position(parts[2])
                                val included = load(parts[1])
                                extend(origin + BlockPos(included.size.x - 1, included.size.y - 1, included.size.z - 1))
                                included.blocks.forEach { (local, state) -> put(origin + local, state) }
                            }
                            "wire" -> {
                                val wirePosition = position(parts[2])
                                val support = wirePosition + BlockPos(0, -1, 0)
                                blocks[support]?.let { require(it.type.isSolid) { "$name wire at $wirePosition lacks solid support" } }
                                    ?: put(support, palette.getValue("support"))
                                put(wirePosition, palette.getValue(parts[1]))
                            }
                            else -> error("unknown layout directive '${parts[0]}' in $name")
                        }
                    }
                    else -> error("content outside a section in $name: $line")
                }
            }
        }

        val size = CellSize(maxX + 1, maxY + 1, maxZ + 1)
        pins.forEach { pin -> require(pin.position.z == size.z - 1) { "$name pin ${pin.name} is not on the routing edge" } }
        return StandardCell(name, BuiltinCells.byId.getValue(CellTypeId(name)), size, pins, blocks.entries.map { it.key to it.value })
    }
}
