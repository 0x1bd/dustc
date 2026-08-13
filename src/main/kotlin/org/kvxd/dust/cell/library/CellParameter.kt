package org.kvxd.dust.cell.library

data class CellParameter(
    val name: String,
    val range: IntRange,
    val default: Int? = null,
) {
    init {
        require(name.matches(Regex("[a-z][a-z0-9_]*"))) { "invalid cell parameter '$name'" }
        require(!range.isEmpty()) { "cell parameter '$name' has an empty range" }
        require(default == null || default in range) { "default $default is outside $name range $range" }
    }
}
