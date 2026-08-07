package org.kvxd.dust.cell

@JvmInline
value class CellTypeId(val value: String) {
    init {
        require(value.matches(Regex("[a-z][a-z0-9-]*"))) { "invalid cell type id '$value'" }
    }

    override fun toString(): String = value
}
