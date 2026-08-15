package org.kvxd.dust.lang.elaboration.body

import org.kvxd.dust.lang.elaboration.model.ElaboratedValue

internal class ElaborationEnvironment(private val parent: ElaborationEnvironment? = null) {
    val bindings: MutableMap<String, Binding> = linkedMapOf()
    val placementTargets: MutableMap<String, ElaboratedValue> = linkedMapOf()

    fun find(name: String): Binding? = bindings[name] ?: parent?.find(name)

    fun findPlacementTarget(name: String): ElaboratedValue? =
        placementTargets[name] ?: parent?.findPlacementTarget(name)

    data class Binding(var value: ElaboratedValue, val mutable: Boolean)
}
