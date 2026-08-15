package org.kvxd.dust.lang.elaboration.model

import org.kvxd.dust.lang.elaboration.display.DisplayElaboration
import org.kvxd.dust.netlist.Signal

internal sealed interface ElaboratedValue {
    data class Signals(val signals: List<Signal>) : ElaboratedValue

    data class DisplayWrite(val write: DisplayElaboration.Write) : ElaboratedValue {
        val signals: List<Signal> = write.signals
    }

    data class Bundle(val outputs: Map<String, ElaboratedValue>) : ElaboratedValue

    data class Integer(val value: Int) : ElaboratedValue
}
