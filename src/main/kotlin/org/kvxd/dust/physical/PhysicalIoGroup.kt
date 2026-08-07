package org.kvxd.dust.physical

data class PhysicalIoGroup(
    val name: String?,
    val direction: PhysicalIoDirection,
    val signals: List<String>,
) {
    init {
        require(signals.isNotEmpty()) { "an I/O group cannot be empty" }
        require(signals.distinct().size == signals.size) { "I/O group '$name' repeats a signal" }
    }
}
