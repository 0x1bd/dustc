package org.kvxd.dust.device

class BooleanProperty(name: String) : Property<Boolean>(name) {
    override val values: List<Boolean> = listOf(false, true)
    override fun parse(text: String): Boolean? = text.toBooleanStrictOrNull()
    override fun print(value: Boolean): String = value.toString()
}
