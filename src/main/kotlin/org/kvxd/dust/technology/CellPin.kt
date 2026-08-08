package org.kvxd.dust.technology

import org.kvxd.dust.device.BlockPos

data class CellPin(
    val name: String,
    val direction: PinDirection,
    val position: BlockPos,
    val port: String = name.substringBefore('['),
    val bit: Int = name.substringAfter('[', "0").substringBefore(']').toIntOrNull() ?: 0,

    val allowsHorizontalAbutment: Boolean = true,

    val accessesFromSouth: Boolean = false,

    val branchOffsetX: Int = 0,
    val driveStrength: Int = 15,
    val requiredStrength: Int = 1,
)
