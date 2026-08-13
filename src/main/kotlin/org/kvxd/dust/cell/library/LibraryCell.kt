package org.kvxd.dust.cell.library

import org.kvxd.dust.cell.definition.CellType
import org.kvxd.dust.technology.StandardCell

data class LibraryCell(
    val logicalType: CellType,
    val physicalView: StandardCell,
)
