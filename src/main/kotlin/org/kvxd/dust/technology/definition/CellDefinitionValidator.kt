package org.kvxd.dust.technology.definition

import org.kvxd.dust.device.block.BlockState
import org.kvxd.dust.device.block.BlockType
import org.kvxd.dust.device.block.ContainerBlockEntity
import org.kvxd.dust.device.block.SignBlockEntity

internal object CellDefinitionValidator {
    fun validate(definition: CellDefinition, templates: Set<String>) {
        try {
            validateDefinition(definition, templates)
        } catch (exception: IllegalArgumentException) {
            val location = definition.sourceLocations["definition"]
            if (location == null || exception.message.orEmpty().startsWith("${location.sourceName}:")) throw exception
            location.error(exception.message ?: "invalid cell definition")
        }
    }

    private fun validateDefinition(definition: CellDefinition, templates: Set<String>) {
        require(definition.name.matches(Regex("[a-z][a-z0-9-]*"))) { "invalid cell name '${definition.name}'" }
        require(definition.palette.map { it.symbol }.distinct().size == definition.palette.size) {
            "${definition.name} repeats a palette symbol"
        }
        require(definition.palette.all { it.template in templates || BlockState.parseOrNull(it.template) != null }) {
            "${definition.name} references an unknown block template"
        }
        require(definition.pins.isNotEmpty()) { "${definition.name} has no pins" }
        require(definition.pins.map { it.name }.distinct().size == definition.pins.size) {
            "${definition.name} repeats a pin name"
        }
        definition.pins.forEach { pin ->
            require(pin.position.x >= 0 && pin.position.y >= 0 && pin.position.z >= 0) {
                "${definition.name} pin ${pin.name} has a negative position"
            }
            require(pin.branchOffsetX >= 0) { "${definition.name} pin ${pin.name} has a negative branch offset" }
            require(pin.driveStrength > 0 && pin.requiredStrength > 0) {
                "${definition.name} pin ${pin.name} has a non-positive signal strength"
            }
        }
        require(definition.layers.map { it.y }.distinct().size == definition.layers.size) {
            "${definition.name} repeats a layer"
        }
        val paletteSymbols = definition.palette.mapTo(hashSetOf()) { it.symbol }
        definition.layers.forEach { layer ->
            require(layer.y >= 0 && layer.rows.isNotEmpty()) { "${definition.name} has an invalid layer ${layer.y}" }
            require(layer.rows.map { it.length }.distinct().size == 1) { "${definition.name} layer ${layer.y} is ragged" }
            layer.rows.flatMap(String::toList).filter { it != '.' }.forEach { symbol ->
                require(symbol in paletteSymbols) { "${definition.name} layer ${layer.y} uses undefined symbol '$symbol'" }
            }
        }
        definition.layout.forEach { entry ->
            val position = when (entry) {
                is CellLayoutEntry.Include -> entry.origin
                is CellLayoutEntry.Wire -> entry.position
                is CellLayoutEntry.Block -> entry.position
            }
            require(position.x >= 0 && position.y >= 0 && position.z >= 0) {
                "${definition.name} has a negative layout position"
            }
            if (entry is CellLayoutEntry.Wire) {
                require(entry.position.y > 0) { "${definition.name} wire at ${entry.position} cannot have support below it" }
                require(entry.template in templates || BlockState.parseOrNull(entry.template) != null) {
                    "${definition.name} references unknown wire template '${entry.template}'"
                }
            }
            if (entry is CellLayoutEntry.Block) {
                require(entry.paletteSymbol in paletteSymbols) {
                    "${definition.name} layout uses undefined symbol '${entry.paletteSymbol}'"
                }
            }
        }
        require(definition.blockEntities.map { it.position }.distinct().size == definition.blockEntities.size) {
            "${definition.name} repeats a block-entity position"
        }
        definition.blockEntities.forEach { entity ->
            require(entity.position.x >= 0 && entity.position.y >= 0 && entity.position.z >= 0) {
                "${definition.name} has a block entity at a negative position"
            }
        }
        require(definition.observations.map { it.name }.distinct().size == definition.observations.size) {
            "${definition.name} repeats an observation name"
        }
        require(definition.observations.map { it.position }.distinct().size == definition.observations.size) {
            "${definition.name} repeats an observation position"
        }
        definition.observations.forEach { observation ->
            require(observation.position.x >= 0 && observation.position.y >= 0 && observation.position.z >= 0) {
                "${definition.name} observation ${observation.name} has a negative position"
            }
        }
        require(definition.layers.isNotEmpty() || definition.layout.isNotEmpty()) { "${definition.name} has no geometry" }
    }

    fun validateBlockEntities(definition: CellDefinition, blocks: Map<org.kvxd.dust.device.geometry.BlockPos, BlockState>) {
        definition.blockEntities.forEachIndexed { index, entity ->
            val expected = when (entity.blockEntity) {
                is ContainerBlockEntity -> BlockType.BARREL
                is SignBlockEntity -> BlockType.OAK_WALL_SIGN
            }
            if (blocks[entity.position]?.type != expected) {
                val message = "${definition.name} block entity at ${entity.position} needs ${expected.id}"
                definition.sourceLocations["block-entity:$index"]?.error(message) ?: throw IllegalArgumentException(message)
            }
        }
    }
}
