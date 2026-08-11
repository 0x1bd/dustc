package org.kvxd.dust.technology.definition

import org.kvxd.dust.device.geometry.BlockPos
import org.kvxd.dust.technology.PinDirection

internal object CellDefinitionParser {
    fun parse(sourceName: String, text: String): CellDefinition {
        val lines = text.lineSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("//") }
            .toList()
        require(lines.isNotEmpty()) { "$sourceName is empty" }
        val declaration = lines.first().split(Regex("\\s+"))
        require(declaration.size == 2 && declaration[0] == "cell") { "$sourceName must start with 'cell <name>'" }

        val palette = mutableListOf<CellPaletteEntry>()
        val pins = mutableListOf<CellPinDefinition>()
        val layers = mutableListOf<CellLayerDefinition>()
        val layout = mutableListOf<CellLayoutEntry>()
        var section = ""
        var layerY: Int? = null
        var layerRows = mutableListOf<String>()

        fun finishLayer() {
            layerY?.let { layers += CellLayerDefinition(it, layerRows.toList()) }
            layerY = null
            layerRows = mutableListOf()
        }

        lines.drop(1).forEach { line ->
            if (line in SECTIONS) {
                finishLayer()
                section = line.dropLast(1)
                return@forEach
            }
            when (section) {
                "palette" -> {
                    val parts = line.split(" = ", limit = 2)
                    require(parts.size == 2 && parts[0].length == 1) { "invalid palette entry '$line' in $sourceName" }
                    palette += CellPaletteEntry(parts[0].single(), parts[1])
                }
                "pins" -> pins += parsePin(sourceName, line)
                "layers" -> if (line.startsWith('@')) {
                    finishLayer()
                    layerY = line.drop(1).toIntOrNull()
                    requireNotNull(layerY) { "invalid layer '$line' in $sourceName" }
                } else {
                    require(layerY != null) { "layer row without a y coordinate in $sourceName" }
                    layerRows += line
                }
                "layout" -> layout += parseLayout(sourceName, line)
                else -> error("content outside a section in $sourceName: $line")
            }
        }
        finishLayer()
        return CellDefinition(declaration[1], palette, pins, layers, layout)
    }

    private fun parsePin(sourceName: String, line: String): CellPinDefinition {
        val parts = line.split(Regex("\\s+"))
        require(parts.size >= 3) { "invalid pin '$line' in $sourceName" }
        val options = parts.drop(3).associate { option ->
            require('=' in option) { "invalid pin option '$option' in $sourceName" }
            option.substringBefore('=') to option.substringAfter('=')
        }
        val known = setOf("abut", "south", "branch", "drive", "required")
        require(options.keys.all { it in known }) { "unknown pin option in '$line' in $sourceName" }
        return CellPinDefinition(
            name = parts[0],
            direction = runCatching { PinDirection.valueOf(parts[1].uppercase()) }
                .getOrElse { throw IllegalArgumentException("invalid pin direction '${parts[1]}' in $sourceName") },
            position = position(sourceName, parts[2]),
            allowsHorizontalAbutment = booleanOption(sourceName, options, "abut", true),
            accessesFromSouth = booleanOption(sourceName, options, "south", false),
            branchOffsetX = integerOption(sourceName, options, "branch", 0),
            driveStrength = integerOption(sourceName, options, "drive", 15),
            requiredStrength = integerOption(sourceName, options, "required", 1),
        )
    }

    private fun parseLayout(sourceName: String, line: String): CellLayoutEntry {
        val parts = line.split(Regex("\\s+"))
        require(parts.size == 3) { "invalid layout entry '$line' in $sourceName" }
        return when (parts[0]) {
            "include" -> CellLayoutEntry.Include(parts[1], position(sourceName, parts[2]))
            "wire" -> CellLayoutEntry.Wire(parts[1], position(sourceName, parts[2]))
            else -> throw IllegalArgumentException("unknown layout directive '${parts[0]}' in $sourceName")
        }
    }

    private fun position(sourceName: String, text: String): BlockPos {
        val values = text.removePrefix("@").split(',').map { it.toIntOrNull() }
        require(text.startsWith('@') && values.size == 3 && values.all { it != null }) {
            "invalid position '$text' in $sourceName"
        }
        return BlockPos(checkNotNull(values[0]), checkNotNull(values[1]), checkNotNull(values[2]))
    }

    private fun booleanOption(sourceName: String, options: Map<String, String>, name: String, default: Boolean): Boolean =
        options[name]?.toBooleanStrictOrNull()
            ?: if (name in options) throw IllegalArgumentException("$name must be true or false in $sourceName") else default

    private fun integerOption(sourceName: String, options: Map<String, String>, name: String, default: Int): Int =
        options[name]?.toIntOrNull()
            ?: if (name in options) throw IllegalArgumentException("$name must be an integer in $sourceName") else default

    private val SECTIONS = setOf("palette:", "pins:", "layers:", "layout:")
}
