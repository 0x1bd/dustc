package org.kvxd.dust.cell.definition

data class CellPort(
    val name: String,
    val width: Int,
    val direction: PortDirection,
) {
    init {
        require(name.matches(Regex("[a-z][a-z0-9-]*"))) { "invalid port name '$name'" }
        require(width > 0) { "port '$name' has non-positive width $width" }
    }
}
