package org.kvxd.dust.device

data class ItemStack(
    val slot: Int,
    val itemId: String,
    val count: Int,
    val maximumCount: Int = 64,
) {
    init {
        require(slot >= 0)
        require(itemId.matches(Regex("[a-z0-9_.-]+:[a-z0-9_./-]+")))
        require(count in 1..maximumCount)
        require(maximumCount > 0)
    }
}
