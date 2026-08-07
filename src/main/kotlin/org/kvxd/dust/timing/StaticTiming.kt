package org.kvxd.dust.timing

import org.kvxd.dust.cell.PortDirection
import org.kvxd.dust.cell.TimingConstraint
import org.kvxd.dust.netlist.CellInstance
import org.kvxd.dust.netlist.Signal
import org.kvxd.dust.physical.PhysicalDesign
import org.kvxd.dust.physical.PlacedCell

object StaticTiming {
    fun analyse(design: PhysicalDesign): TimingReport {
        val cells = design.cells.associateBy { it.name }
        var criticalPath = 0
        val violations = mutableListOf<HoldViolation>()
        var worstSlack = Int.MAX_VALUE

        design.netlist.inputs.forEach { (inputName, inputSignal) ->
            val walk = propagate(design, cells, inputSignal)
            design.netlist.outputs.values.forEach { signal ->
                if (walk.reached[signal.index]) criticalPath = maxOf(criticalPath, walk.latest[signal.index])
            }

            design.netlist.instances.forEach { instance ->
                val cell = cells[instance.name] ?: return@forEach
                instance.type.timing.constraints.forEach { constraint ->
                    when (constraint) {
                        is TimingConstraint.SetupHold -> {
                            val data = portArrival(design, walk, instance, cell, constraint.dataPort, earliest = true)
                                ?: return@forEach
                            val clock = portArrival(design, walk, instance, cell, constraint.clockPort, earliest = false)
                                ?: return@forEach
                            val slack = data - (clock + constraint.holdTicks)
                            worstSlack = minOf(worstSlack, slack)
                            if (slack < 0) {
                                violations += HoldViolation(instance.name, inputName, data, clock, slack)
                            }
                        }
                    }
                }
            }
        }
        return TimingReport(
            criticalPath,
            if (worstSlack == Int.MAX_VALUE) 0 else worstSlack,
            violations,
        )
    }

    private fun propagate(
        design: PhysicalDesign,
        cells: Map<String, PlacedCell>,
        from: Signal,
    ): Walk {
        val walk = Walk(design.netlist.signals)
        walk.reached[from.index] = true

        design.netlist.instances.forEach { instance ->
            val cell = cells[instance.name] ?: return@forEach
            instance.type.ports.filter { it.direction == PortDirection.OUTPUT }.forEach outputLoop@{ outputPort ->
                outputPortBits@ for (outputBit in 0 until outputPort.width) {
                    val outputSignal = instance.connections.getValue(outputPort.name)[outputBit]
                    var earliest = Int.MAX_VALUE
                    var latest = Int.MIN_VALUE
                    instance.type.timing.arcs.filter { arc ->
                        arc.toPort == outputPort.name && (arc.toBit == null || arc.toBit == outputBit)
                    }.forEach { arc ->
                        val inputPort = instance.type.port(arc.fromPort)
                        val inputBits = arc.fromBit?.let(::listOf) ?: (0 until inputPort.width).toList()
                        inputBits.forEach { inputBit ->
                            val inputSignal = instance.connections.getValue(inputPort.name)[inputBit]
                            if (!walk.reached[inputSignal.index]) return@forEach
                            val pin = physicalPin(cell, inputPort.name, inputBit)
                            val wire = design.routeDelayTicks[cell.pin(pin)] ?: 0
                            earliest = minOf(earliest, walk.earliest[inputSignal.index] + wire + arc.minDelay)
                            latest = maxOf(latest, walk.latest[inputSignal.index] + wire + arc.maxDelay)
                        }
                    }
                    if (latest == Int.MIN_VALUE) continue@outputPortBits
                    walk.reached[outputSignal.index] = true
                    walk.earliest[outputSignal.index] = earliest
                    walk.latest[outputSignal.index] = latest
                }
            }
        }
        return walk
    }

    private val org.kvxd.dust.cell.TimingArc.minDelay: Int
        get() = minOf(rise.minTicks, fall.minTicks)
    private val org.kvxd.dust.cell.TimingArc.maxDelay: Int
        get() = maxOf(rise.maxTicks, fall.maxTicks)

    private fun portArrival(
        design: PhysicalDesign,
        walk: Walk,
        instance: CellInstance,
        cell: PlacedCell,
        port: String,
        earliest: Boolean,
    ): Int? {
        val arrivals = instance.connections.getValue(port).mapIndexedNotNull { bit, signal ->
            if (!walk.reached[signal.index]) return@mapIndexedNotNull null
            val pin = physicalPin(cell, port, bit)
            val wire = design.routeDelayTicks[cell.pin(pin)] ?: 0
            (if (earliest) walk.earliest[signal.index] else walk.latest[signal.index]) + wire
        }
        return if (earliest) arrivals.minOrNull() else arrivals.maxOrNull()
    }

    private fun physicalPin(cell: PlacedCell, port: String, bit: Int): String =
        cell.cell.pins.single { it.port == port && it.bit == bit }.name

    private class Walk(signals: Int) {
        val reached: BooleanArray = BooleanArray(signals)
        val earliest: IntArray = IntArray(signals)
        val latest: IntArray = IntArray(signals)
    }
}
