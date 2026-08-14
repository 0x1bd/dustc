package org.kvxd.dust.cell.library

import org.kvxd.dust.cell.behavior.CellBehavior
import org.kvxd.dust.cell.definition.CellPort
import org.kvxd.dust.cell.definition.CellType
import org.kvxd.dust.cell.definition.CellTypeId
import org.kvxd.dust.cell.definition.PortDirection
import org.kvxd.dust.cell.timing.CellTiming

object DisplayPixelCell {
    const val NAME: String = "display-pixel"

    val logicalType: CellType = CellType(
        CellTypeId(NAME),
        listOf(CellPort("pixel", 1, PortDirection.INPUT)),
        CellBehavior.Combinational { emptyMap() },
        CellTiming.NONE,
    )
}
