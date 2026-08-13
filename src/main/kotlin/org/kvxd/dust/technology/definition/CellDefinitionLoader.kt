package org.kvxd.dust.technology.definition

import org.kvxd.dust.cell.definition.CellType
import org.kvxd.dust.cell.library.CellParameter
import org.kvxd.dust.cell.library.CellProvider
import org.kvxd.dust.device.block.BlockState
import org.kvxd.dust.technology.CellImplementation
import org.kvxd.dust.technology.StandardCell

internal class CellDefinitionLoader(
    private val templates: Map<String, BlockState>,
    private val logicalType: (String, Map<String, Int>) -> CellType,
) {
    constructor(templates: Map<String, BlockState>, logicalType: (String) -> CellType) :
        this(templates, { name, _ -> logicalType(name) })

    private val cells = mutableMapOf<CellKey, StandardCell>()
    private val loading = mutableSetOf<CellKey>()
    private val sourceTexts = mutableMapOf<String, Pair<String, String>>()

    @Synchronized
    fun parameters(name: String): List<CellParameter> {
        val (resource, text) = source(name)
        return CellDefinitionParser.parameters(resource, text)
    }

    @Synchronized
    fun load(
        name: String,
        parameters: List<Int> = emptyList(),
        implementation: CellImplementation = CellImplementation.Standard,
    ): StandardCell = load(name, parameters, implementation, null)

    @Synchronized
    fun provider(
        name: String,
        logicalView: (Map<String, Int>) -> CellType,
        implementation: CellImplementation = CellImplementation.Standard,
    ): CellProvider = CellProvider(
        name = name,
        parameters = parameters(name),
        logicalView = logicalView,
        physicalView = { logical, arguments ->
            val ordered = parameters(name).map { arguments.getValue(it.name) }
            load(name, ordered, implementation, logical)
        },
    )

    private fun load(
        name: String,
        parameters: List<Int>,
        implementation: CellImplementation,
        suppliedLogicalType: CellType?,
    ): StandardCell {
        val (resource, text) = source(name)
        val declaration = CellDefinitionParser.parameters(resource, text)
        require(parameters.size <= declaration.size) {
            "$name accepts ${declaration.size} cell parameters, got ${parameters.size}"
        }
        val arguments = declaration.mapIndexed { index, parameter ->
            val value = parameters.getOrNull(index) ?: requireNotNull(parameter.default) {
                "$name needs cell parameter '${parameter.name}'"
            }
            require(value in parameter.range) {
                "$name parameter '${parameter.name}' must be in ${parameter.range}, got $value"
            }
            parameter.name to value
        }.toMap()
        val key = CellKey(name, declaration.map { arguments.getValue(it.name) })
        return cells[key]?.also { cached ->
            require(cached.implementation == implementation || implementation == CellImplementation.Standard) {
                "$name was already loaded as ${cached.implementation}, not $implementation"
            }
            require(suppliedLogicalType == null || cached.logicalType === suppliedLogicalType) {
                "${key.display()} was already loaded for a different logical specialization"
            }
        } ?: run {
            require(loading.add(key)) { "cyclic cell inclusion involving ${key.display()}" }
            try {
                val definition = CellDefinitionParser.parse(resource, text, arguments)
                require(definition.name == name) { "$resource: cell definition name mismatch for $name" }
                CellDefinitionValidator.validate(definition, templates.keys)
                StandardCellFactory(templates) { included, includedArguments -> load(included, includedArguments) }
                    .create(definition, suppliedLogicalType ?: logicalType(name, arguments), implementation)
                    .also { cells[key] = it }
            } finally {
                loading.remove(key)
            }
        }
    }

    private fun source(name: String): Pair<String, String> = sourceTexts.getOrPut(name) {
        val resource = "/org/kvxd/dust/technology/cells/$name.txt"
        val text = requireNotNull(javaClass.getResourceAsStream(resource)) {
            "missing cell definition for $name"
        }.bufferedReader().use { it.readText() }
        resource to text
    }

    private data class CellKey(
        val name: String,
        val arguments: List<Int>,
    ) {
        fun display(): String = if (arguments.isEmpty()) name else "$name<${arguments.joinToString(",")}>"
    }
}
