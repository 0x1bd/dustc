package org.kvxd.dust.technology.definition

import org.kvxd.dust.device.block.BlockEntity
import org.kvxd.dust.device.geometry.BlockPos

internal data class CellBlockEntityDefinition(
    val position: BlockPos,
    val blockEntity: BlockEntity,
)
