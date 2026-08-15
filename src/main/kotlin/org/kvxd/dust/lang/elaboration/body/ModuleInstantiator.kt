package org.kvxd.dust.lang.elaboration.body

import org.kvxd.dust.lang.elaboration.model.ElaboratedValue
import org.kvxd.dust.lang.elaboration.module.SpecializationKey
import org.kvxd.dust.lang.elaboration.module.SpecializedModule
import org.kvxd.dust.netlist.BooleanNetlistBuilder

internal interface ModuleInstantiator {
    fun instantiate(
        module: SpecializedModule,
        builder: BooleanNetlistBuilder,
        inputs: Map<String, ElaboratedValue>,
        callStack: List<SpecializationKey>,
    ): Map<String, ElaboratedValue>
}
