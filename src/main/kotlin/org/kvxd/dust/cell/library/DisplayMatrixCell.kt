package org.kvxd.dust.cell.library

import org.kvxd.dust.cell.behavior.CellBehavior
import org.kvxd.dust.cell.definition.CellPort
import org.kvxd.dust.cell.definition.CellType
import org.kvxd.dust.cell.definition.CellTypeId
import org.kvxd.dust.cell.definition.PortDirection
import org.kvxd.dust.cell.timing.CellTiming

object DisplayMatrixCell {
    const val NAME: String = "display_matrix"

    fun logicalType(parameters: Map<String, Int>): CellType {
        val width = parameters.getValue("WIDTH")
        val height = parameters.getValue("HEIGHT")
        return CellType(
            CellTypeId("display-matrix-${width}x$height"),
            listOf(CellPort("pixels", width * height, PortDirection.INPUT)),
            CellBehavior.Combinational { emptyMap() },
            CellTiming.NONE,
        )
    }
}
