package org.kvxd.dust.cell.timing

data class DelayRange(val minTicks: Int, val maxTicks: Int) {
    init {
        require(minTicks >= 0 && maxTicks >= minTicks)
    }
}
