package org.kvxd.dust.technology.definition

import org.kvxd.dust.device.geometry.BlockPos

internal sealed interface CellLayoutEntry {
    data class Include(val cell: String, val origin: BlockPos) : CellLayoutEntry
    data class Wire(val template: String, val position: BlockPos) : CellLayoutEntry
}
