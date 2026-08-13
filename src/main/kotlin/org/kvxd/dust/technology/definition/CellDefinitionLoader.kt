package org.kvxd.dust.technology.definition

import org.kvxd.dust.device.block.BlockState
import org.kvxd.dust.cell.definition.CellType
import org.kvxd.dust.technology.CellImplementation
import org.kvxd.dust.technology.StandardCell

internal class CellDefinitionLoader(
    private val templates: Map<String, BlockState>,
    private val logicalType: (String) -> CellType,
) {
    private val cells = mutableMapOf<String, StandardCell>()
    private val loading = mutableSetOf<String>()

    fun load(
        name: String,
        implementation: CellImplementation = CellImplementation.Standard,
    ): StandardCell = cells[name]?.also { cached ->
        require(cached.implementation == implementation) {
            "$name was already loaded as ${cached.implementation}, not $implementation"
        }
    } ?: run {
        require(loading.add(name)) { "cyclic cell inclusion involving $name" }
        try {
            val resource = "/org/kvxd/dust/technology/cells/$name.txt"
            val text = requireNotNull(javaClass.getResourceAsStream(resource)) {
                "missing cell definition for $name"
            }.bufferedReader().use { it.readText() }
            val definition = CellDefinitionParser.parse(resource, text)
            require(definition.name == name) { "cell definition name mismatch for $name" }
            CellDefinitionValidator.validate(definition, templates.keys)
            StandardCellFactory(templates) { included -> load(included) }
                .create(definition, logicalType(name), implementation)
                .also { cells[name] = it }
        } finally {
            loading.remove(name)
        }
    }
}
