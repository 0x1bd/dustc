package org.kvxd.dust.cell.library

import org.kvxd.dust.DisplayDimensions
import org.kvxd.dust.cell.definition.CellType
import org.kvxd.dust.cell.definition.CellTypeId
import org.kvxd.dust.netlist.BooleanNetlistBuilder
import org.kvxd.dust.physical.io.PhysicalIoEdge
import org.kvxd.dust.technology.StandardCell

class CellLibrary(
    providers: List<CellProvider>,
    private val displayCell: DisplayCell? = null,
) {
    private val providers: Map<String, CellProvider> = providers.associateBy { it.name }.also { indexed ->
        require(indexed.size == providers.size) { "cell library repeats a provider name" }
    }
    private val specializations = linkedMapOf<CellSpecialization, LibraryCell>()
    private val byType = linkedMapOf<CellTypeId, LibraryCell>()

    init {
        providers.filter { provider -> provider.parameters.all { it.default != null } }
            .forEach { specialize(it.name) }
    }

    @Synchronized
    fun specialize(name: String, parameters: List<Int> = emptyList()): LibraryCell {
        val provider = requireNotNull(providers[name]) { "unknown cell provider '$name'" }
        val arguments = provider.arguments(parameters)
        val key = CellSpecialization(name, provider.parameters.map { arguments.getValue(it.name) })
        return specializations[key] ?: provider.create(arguments).also { created ->
            val previous = byType.putIfAbsent(created.logicalType.id, created)
            require(previous == null) {
                "cell specializations $key and ${specializations.entries.single { it.value === previous }.key} " +
                    "both produce logical type ${created.logicalType.id}"
            }
            specializations[key] = created
        }
    }

    @Synchronized
    fun physicalView(type: CellType): StandardCell? {
        val registered = byType[type.id] ?: return null
        require(registered.logicalType === type) {
            "logical type ${type.id} is not the definition registered with this cell library"
        }
        return registered.physicalView
    }

    @Synchronized
    fun registeredCells(): List<LibraryCell> = specializations.values.toList()

    fun providerNames(): Set<String> = providers.keys

    fun provider(name: String): CellProvider? = providers[name]

    internal fun displayDimensions(width: Int, height: Int): DisplayDimensions {
        val dimensions = DisplayDimensions(width, height)
        requireNotNull(displayCell) { "this cell library does not provide displays" }.validate(dimensions)
        return dimensions
    }

    internal fun displayOutputEdge(): PhysicalIoEdge =
        requireNotNull(displayCell) { "this cell library does not provide displays" }.outputEdge

    internal fun instantiateDisplay(
        builder: BooleanNetlistBuilder,
        name: String,
        dimensions: DisplayDimensions,
        inputs: DisplayCell.Inputs,
    ) {
        requireNotNull(displayCell) { "this cell library does not provide displays" }
            .instantiate(this, builder, name, dimensions, inputs)
    }

    private data class CellSpecialization(
        val provider: String,
        val parameters: List<Int>,
    )
}
