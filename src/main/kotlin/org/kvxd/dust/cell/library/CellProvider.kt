package org.kvxd.dust.cell.library

import org.kvxd.dust.cell.definition.CellType
import org.kvxd.dust.technology.StandardCell

class CellProvider(
    val name: String,
    val parameters: List<CellParameter> = emptyList(),
    private val logicalView: (Map<String, Int>) -> CellType,
    private val physicalView: (CellType, Map<String, Int>) -> StandardCell,
) {
    init {
        require(name.matches(Regex("[a-z][a-z0-9_-]*"))) { "invalid cell provider '$name'" }
        require(parameters.map { it.name }.distinct().size == parameters.size) {
            "$name repeats a cell parameter"
        }
        val firstDefault = parameters.indexOfFirst { it.default != null }
        require(firstDefault < 0 || parameters.drop(firstDefault).all { it.default != null }) {
            "$name has a required parameter after an optional parameter"
        }
    }

    internal fun arguments(values: List<Int>): Map<String, Int> {
        require(values.size <= parameters.size) {
            "$name accepts ${parameters.size} cell parameters, got ${values.size}"
        }
        return parameters.mapIndexed { index, parameter ->
            val value = values.getOrNull(index) ?: requireNotNull(parameter.default) {
                "$name needs cell parameter '${parameter.name}'"
            }
            require(value in parameter.range) {
                "$name parameter '${parameter.name}' must be in ${parameter.range}, got $value"
            }
            parameter.name to value
        }.toMap()
    }

    internal fun create(arguments: Map<String, Int>): LibraryCell {
        val logical = logicalView(arguments)
        val physical = physicalView(logical, arguments)
        require(physical.logicalType === logical) {
            "$name physical view was built for ${physical.logicalType.id}, not the logical specialization ${logical.id}"
        }
        return LibraryCell(logical, physical)
    }

    companion object {
        fun fixed(
            logicalType: CellType,
            physicalView: (CellType) -> StandardCell,
            name: String = logicalType.id.value,
        ): CellProvider = CellProvider(
            name,
            logicalView = { logicalType },
            physicalView = { logical, _ -> physicalView(logical) },
        )
    }
}
