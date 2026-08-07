package org.kvxd.dust

class CircuitResult internal constructor(private val values: Map<String, ULong>) {
    operator fun get(port: String): ULong = values.getValue(port)
    fun bit(port: String): Boolean = get(port) != 0uL
    fun asMap(): Map<String, ULong> = values
    override fun toString(): String = values.toString()
}
