package org.kvxd.dust.physical.compilation.model

internal data class GlobalSinkPoint(
    val key: GlobalSinkKey,
    val row: Int,
    val x: Int,
)
