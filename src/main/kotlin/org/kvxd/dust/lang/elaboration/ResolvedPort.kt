package org.kvxd.dust.lang.elaboration

import org.kvxd.dust.DisplayDimensions
import org.kvxd.dust.lang.syntax.PortSyntax

internal data class ResolvedPort(
    val syntax: PortSyntax,
    val width: Int,
    val display: DisplayDimensions?,
)
