package org.kvxd.dust.physical.compilation.model

import org.kvxd.dust.device.geometry.BlockPos

internal sealed interface Endpoint {
    val x: Int

    data class Cell(
        val position: BlockPos,
        val allowsHorizontalAbutment: Boolean,
        val sense: ViaSense,
        val driveStrength: Int,
        val requiredStrength: Int,
    ) : Endpoint {
        override val x: Int get() = position.x
    }

    data class Global(val track: GlobalTrack, val sense: ViaSense, val viaX: Int) : Endpoint {
        override val x: Int get() = viaX
    }
}
