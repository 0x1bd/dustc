package org.kvxd.dust.physical.compilation.model

import org.kvxd.dust.device.geometry.BlockPos
import org.kvxd.dust.physical.design.PlacedCell
import org.kvxd.dust.technology.CellPin

internal data class ConnectedPin(val cell: PlacedCell, val pin: CellPin, val position: BlockPos)
