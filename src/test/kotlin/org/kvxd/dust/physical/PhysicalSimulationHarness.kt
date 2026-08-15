package org.kvxd.dust.physical

import org.kvxd.dust.physical.design.PhysicalDesign
import org.kvxd.dust.sim.GateLevelSimulator

internal class PhysicalSimulationHarness(private val design: PhysicalDesign) {
    private val simulator = GateLevelSimulator(design.matrix)
    private val tickBound = design.matrix.blockCount()

    init {
        simulator.settle(tickBound)
        requireSettled("initial state")
    }

    fun setInput(name: String, value: Boolean) {
        simulator.setInput(design.inputs.getValue(name), value)
    }

    fun setBus(name: String, width: Int, value: ULong) {
        require(width in 1..ULong.SIZE_BITS)
        repeat(width) { bit -> setInput("$name[$bit]", value and (1uL shl bit) != 0uL) }
    }

    fun advance() {
        simulator.advanceUntilIdle(tickBound)
    }

    fun output(name: String): Boolean = simulator.readOutput(design.outputs.getValue(name))

    fun outputBus(name: String, width: Int): ULong {
        require(width in 1..ULong.SIZE_BITS)
        return (0 until width).fold(0uL) { value, bit ->
            if (output("$name[$bit]")) value or (1uL shl bit) else value
        }
    }

    fun requireSettled(context: String = "simulation") {
        check(simulator.unsettled().isEmpty()) {
            "$context did not settle: ${simulator.unsettled().take(6).joinToString("; ")}"
        }
    }
}
