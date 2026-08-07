package org.kvxd.dust.device

sealed class Property<T : Any>(val name: String) {
    abstract val values: List<T>
    abstract fun parse(text: String): T?
    abstract fun print(value: T): String
    open fun accepts(value: T): Boolean = value in values

    @Suppress("UNCHECKED_CAST")
    internal fun cast(value: Any): T = value as T
}
