package org.kvxd.dust.device

class IntProperty(name: String, val min: Int, val max: Int) : Property<Int>(name) {
    override val values: List<Int> = (min..max).toList()
    override fun parse(text: String): Int? = text.toIntOrNull()?.takeIf { it in min..max }
    override fun accepts(value: Int): Boolean = value in min..max
    override fun print(value: Int): String = value.toString()
}
