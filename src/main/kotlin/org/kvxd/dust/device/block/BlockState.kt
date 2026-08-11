package org.kvxd.dust.device.block

import org.kvxd.dust.device.property.Property

class BlockState internal constructor(val type: BlockType, private val values: List<Any>) {

    init {
        require(values.size == type.properties.size) {
            "${type.id} takes ${type.properties.size} property values, got ${values.size}"
        }
    }

    private val cachedHash: Int = 31 * type.hashCode() + values.hashCode()

    operator fun <T : Any> get(property: Property<T>): T {
        val index = type.properties.indexOfFirst { it === property }
        require(index >= 0) { "${type.id} has no property ${property.name}" }
        return property.cast(values[index])
    }

    fun <T : Any> getOrNull(property: Property<T>): T? {
        val index = type.properties.indexOfFirst { it === property }
        return if (index < 0) null else property.cast(values[index])
    }

    fun <T : Any> with(property: Property<T>, value: T): BlockState {
        val index = type.properties.indexOfFirst { it === property }
        require(index >= 0) { "${type.id} has no property ${property.name}" }
        require(property.accepts(value)) { "${property.name}=$value is not legal for ${type.id}" }
        if (values[index] == value) return this
        val next = values.toMutableList()
        next[index] = value
        return BlockState(type, next)
    }

    val component: ComponentKind get() = type.component

    val isAir: Boolean get() = type == BlockType.AIR

    override fun toString(): String {
        if (type.properties.isEmpty()) return type.id
        return buildString {
            append(type.id)
            append('[')
            type.properties
                .mapIndexed { index, property -> property.name to printValue(property, values[index]) }
                .sortedBy { it.first }
                .forEachIndexed { index, (name, printed) ->
                    if (index > 0) append(',')
                    append(name).append('=').append(printed)
                }
            append(']')
        }
    }

    override fun equals(other: Any?): Boolean =
        this === other || (other is BlockState && type == other.type && values == other.values)

    override fun hashCode(): Int = cachedHash

    private fun <T : Any> printValue(property: Property<T>, value: Any): String = property.print(property.cast(value))

    companion object {

        val AIR: BlockState = BlockType.AIR.defaultState

        fun of(type: BlockType): BlockState = type.defaultState

        fun of(type: BlockType, values: Map<Property<*>, Any>): BlockState {
            var state = type.defaultState
            for ((property, value) in values) {
                state = setUnchecked(state, property, value)
            }
            return state
        }

        fun parse(text: String): BlockState = parseOrNull(text)
            ?: throw IllegalArgumentException("not a block state known to MCHPRS: '$text'")

        fun parseOrNull(text: String): BlockState? {
            val trimmed = text.trim()
            val bracket = trimmed.indexOf('[')
            if (bracket < 0) {
                return BlockType.fromId(trimmed)?.defaultState
            }
            if (!trimmed.endsWith(']')) return null
            val type = BlockType.fromId(trimmed.substring(0, bracket)) ?: return null

            val body = trimmed.substring(bracket + 1, trimmed.length - 1)
            return if (body.isBlank()) type.defaultState else applyProperties(type, body)
        }

        private fun applyProperties(type: BlockType, body: String): BlockState? {
            var state = type.defaultState
            for (entry in body.split(',')) {
                val equals = entry.indexOf('=')
                if (equals < 0) return null
                val property = type.property(entry.substring(0, equals).trim()) ?: return null
                val value = property.parse(entry.substring(equals + 1).trim()) ?: return null
                state = setUnchecked(state, property, value)
            }
            return state
        }

        @Suppress("UNCHECKED_CAST")
        private fun setUnchecked(state: BlockState, property: Property<*>, value: Any): BlockState =
            state.with(property as Property<Any>, value)
    }
}
