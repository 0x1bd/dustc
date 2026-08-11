package org.kvxd.dust.device.block

import org.kvxd.dust.device.geometry.BlockPos

fun interface BlockAccess {
    fun blockAt(pos: BlockPos): BlockState

    fun blockEntityAt(pos: BlockPos): BlockEntity? = null

    fun analogOutputAt(pos: BlockPos): Int? = null
}
