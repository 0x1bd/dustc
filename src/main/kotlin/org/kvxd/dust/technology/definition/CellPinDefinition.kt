package org.kvxd.dust.technology.definition

import org.kvxd.dust.device.geometry.BlockPos
import org.kvxd.dust.technology.PinDirection

internal data class CellPinDefinition(
    val name: String,
    val direction: PinDirection,
    val position: BlockPos,
    val allowsHorizontalAbutment: Boolean,
    val accessesFromSouth: Boolean,
    val branchOffsetX: Int,
    val driveStrength: Int,
    val requiredStrength: Int,
)
