package org.kvxd.dust.lang.elaboration

internal data class SpecializationKey(val module: String, val parameters: List<Int>) {
    override fun toString(): String =
        if (parameters.isEmpty()) module else "$module<${parameters.joinToString(", ")}>"
}
