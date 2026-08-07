package org.kvxd.dust.physical

data class PhysicalIoLayout(val groups: List<PhysicalIoGroup>) {
    init {
        require(groups.isNotEmpty()) { "an I/O layout cannot be empty" }
    }
}
