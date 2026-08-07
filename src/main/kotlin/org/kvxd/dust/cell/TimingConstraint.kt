package org.kvxd.dust.cell

sealed interface TimingConstraint {
    data class SetupHold(
        val dataPort: String,
        val clockPort: String,
        val clockEdge: Edge,
        val setupTicks: Int,
        val holdTicks: Int,
    ) : TimingConstraint {
        init {
            require(setupTicks >= 0 && holdTicks >= 0)
        }
    }

}
