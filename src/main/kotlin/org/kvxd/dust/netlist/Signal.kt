package org.kvxd.dust.netlist

class Signal internal constructor(val index: Int) {
    override fun equals(other: Any?): Boolean = other is Signal && index == other.index
    override fun hashCode(): Int = index
    override fun toString(): String = "n$index"
}
