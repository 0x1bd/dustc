package org.kvxd.dust.physical

import kotlin.random.Random
import org.kvxd.dust.device.block.ComponentKind
import org.kvxd.dust.physical.design.PhysicalDesign
import org.kvxd.dust.physical.design.PlacedCell
import org.kvxd.dust.netlist.BooleanNetlist
import org.kvxd.dust.netlist.Signal
import org.kvxd.dust.netlist.booleanNetlist
import org.kvxd.dust.sim.GateLevelSimulator

object RouterHarness {
    data class Quality(
        val gates: Int,
        val nodes: Int,
        val repeaters: Int,
        val torches: Int,
        val routeBlocks: Int,
        val width: Int,
        val height: Int,
        val length: Int,
        val blocks: Int,
        val rows: Int,
        val lanes: Int,
        val globals: Int,
    ) {
        val nodesPerGate: Double get() = nodes.toDouble() / gates
        val area: Long get() = width.toLong() * length

        override fun toString(): String =
            "gates=$gates nodes=$nodes (${"%.1f".format(nodesPerGate)}/gate) " +
                "route=$routeBlocks extent=${width}x${height}x$length blocks=$blocks " +
                "rows=$rows lanes=$lanes globals=$globals"
    }

    fun measure(design: PhysicalDesign): Quality {
        var repeaters = 0
        var torches = 0
        val interfaceNodes = design.cells
            .filter { it.name.startsWith("input-") || it.name.startsWith("output-") }
            .flatMapTo(mutableSetOf()) { cell ->
                cell.cell.blocks.mapNotNull { (local, state) ->
                    if (state.type.component == ComponentKind.REPEATER ||
                        state.type.component == ComponentKind.TORCH
                    ) {
                        cell.origin + local
                    } else {
                        null
                    }
                }
            }
        design.matrix.forEachPosition { x, y, z, state ->
            if (org.kvxd.dust.device.geometry.BlockPos(x, y, z) in interfaceNodes) return@forEachPosition
            when (state.type.component) {
                ComponentKind.REPEATER -> repeaters++
                ComponentKind.TORCH -> torches++
                else -> Unit
            }
        }
        return Quality(
            gates = design.netlist.gates.size,
            nodes = repeaters + torches,
            repeaters = repeaters,
            torches = torches,
            routeBlocks = design.routes.sumOf { it.routeBlocks.size },
            width = design.matrix.width,
            height = design.matrix.height,
            length = design.matrix.length,
            blocks = design.matrix.blockCount(),
            rows = design.rowCount,
            lanes = design.laneCount,
            globals = design.globalNetCount,
        )
    }

    fun proveElectrically(design: PhysicalDesign, vectors: Int = 8, seed: Int = 1) {
        val netlist = design.netlist
        val simulator = GateLevelSimulator(design.matrix)
        val bound = design.matrix.blockCount()
        simulator.settle(bound)
        check(simulator.unsettled().isEmpty()) { "initial: " + simulator.unsettled().take(6).joinToString("; ") }

        val random = Random(seed)
        repeat(vectors) { vector ->
            val values = netlist.inputs.keys.associateWith { random.nextBoolean() }
            values.forEach { (name, value) ->
                simulator.setInput(checkNotNull(design.inputs[name]), value)
            }
            simulator.advanceUntilIdle(bound)

            val broken = design.routes.mapNotNull { route ->
                val source = simulator.levelAt(route.source)
                val wrong = route.sinks.filter { simulator.levelAt(it) != source }
                if (wrong.isEmpty()) null else "${route.signal} source=$source wrongSinks=${wrong.take(3)}"
            }
            check(broken.isEmpty()) { "vector $vector routes broken: ${broken.take(6)}" }

            val expected = netlist.evaluate(values)
            expected.forEach { (name, want) ->
                val got = simulator.readOutput(checkNotNull(design.outputs[name]))
                check(got == want) { "vector $vector $values: output $name = $got, expected $want" }
            }
            check(simulator.unsettled().isEmpty()) {
                "vector $vector: " + simulator.unsettled().take(6).joinToString("; ")
            }
        }
    }

    fun adder(width: Int): BooleanNetlist = booleanNetlist("adder-$width") {
        val a = List(width) { input("a[$it]") }
        val b = List(width) { input("b[$it]") }
        var carry = input("carry-in")
        repeat(width) { bit ->
            val partial = xor(a[bit], b[bit])
            output("sum[$bit]", xor(partial, carry))
            carry = or(and(a[bit], b[bit]), and(carry, partial))
        }
        output("carry", carry)
    }

    fun decodedMux(groups: Int, width: Int = 8): BooleanNetlist {
        require(groups >= 2 && Integer.bitCount(groups) == 1) { "groups must be a power of two" }
        val addressBits = Integer.numberOfTrailingZeros(groups)
        return booleanNetlist("decoded-mux-${groups}x$width") {
            val writeAddress = List(addressBits) { input("wa[$it]") }
            val readAddress = List(addressBits) { input("ra[$it]") }
            val writeData = List(width) { input("d[$it]") }
            val writeEnable = input("we")
            val stored = List(groups) { group -> List(width) { input("q$group[$it]") } }

            fun decode(address: List<Signal>): List<Signal> {
                val inverted: List<Signal> = address.map { bit -> not(bit) }
                return (0 until groups).map { value ->
                    all(
                        *address.indices
                            .map { bit -> if (value and (1 shl bit) != 0) address[bit] else inverted[bit] }
                            .toTypedArray(),
                    )
                }
            }

            val writeSelect: List<Signal> = decode(writeAddress).map { select -> and(select, writeEnable) }
            val readSelect: List<Signal> = decode(readAddress)

            val next = stored.mapIndexed { group, bits ->
                bits.mapIndexed { bit, held ->
                    or(and(writeSelect[group], writeData[bit]), and(not(writeSelect[group]), held))
                }
            }
            next.forEachIndexed { group, bits ->
                bits.forEachIndexed { bit, signal -> output("n$group[$bit]", signal) }
            }
            repeat(width) { bit ->
                val gated = (0 until groups).map { group -> and(readSelect[group], stored[group][bit]) }
                output("read[$bit]", any(*gated.toTypedArray()))
            }
        }
    }

    fun broadcast(width: Int): BooleanNetlist = booleanNetlist("broadcast-$width") {
        val select = input("select")
        val data = List(width) { input("d[$it]") }
        val gated = data.map { and(select, it) }
        gated.forEachIndexed { index, signal -> output("q[$index]", signal) }
        output("any", gated.reduce(::or))
    }
}
