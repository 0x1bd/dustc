package org.kvxd.dust.lang.elaboration.module

import org.kvxd.dust.lang.syntax.ModuleSyntax

internal data class SpecializedModule(
    val syntax: ModuleSyntax,
    val arguments: Map<String, Int>,
    val ports: List<ResolvedPort>,
    val key: SpecializationKey,
)
