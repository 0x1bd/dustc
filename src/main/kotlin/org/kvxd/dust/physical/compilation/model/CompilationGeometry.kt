package org.kvxd.dust.physical.compilation.model

internal fun Endpoint.targetY(): Int = when (this) {
    is Endpoint.Cell -> position.y
    is Endpoint.Global -> track.planeY
}

internal fun Endpoint.viaSense(): ViaSense = when (this) {
    is Endpoint.Cell -> sense
    is Endpoint.Global -> sense
}

internal fun IntRange.conflicts(other: IntRange, isolation: Int): Boolean =
    !(last + isolation < other.first || other.last + isolation < first)
