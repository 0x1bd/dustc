package org.kvxd.dust.device

class EnumProperty<E : Enum<E>>(name: String, override val values: List<E>) : Property<E>(name) {
    private val byName = values.associateBy { it.name.lowercase() }
    override fun parse(text: String): E? = byName[text]
    override fun print(value: E): String = value.name.lowercase()
}
