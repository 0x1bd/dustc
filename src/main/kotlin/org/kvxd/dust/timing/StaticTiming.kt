package org.kvxd.dust.timing

import org.kvxd.dust.cell.behavior.CellBehavior
import org.kvxd.dust.cell.definition.PortDirection
import org.kvxd.dust.cell.timing.TimingArc
import org.kvxd.dust.cell.timing.TimingConstraint
import org.kvxd.dust.device.geometry.BlockPos
import org.kvxd.dust.netlist.BooleanNetlist
import org.kvxd.dust.netlist.CellInstance
import org.kvxd.dust.netlist.Signal
import org.kvxd.dust.physical.design.PhysicalDesign
import org.kvxd.dust.physical.design.PlacedCell

object StaticTiming {
    fun analyse(
        design: PhysicalDesign,
        clockPeriodTicks: Int? = null,
        maximumClockSkewTicks: Int = 1,
    ): TimingReport = analyse(
        design.netlist,
        design.cells,
        design.routeDelayTicks,
        clockPeriodTicks,
        maximumClockSkewTicks,
    )

    internal fun analyse(
        netlist: BooleanNetlist,
        placedCells: List<PlacedCell>,
        routeDelayTicks: Map<BlockPos, Int>,
        clockPeriodTicks: Int? = null,
        maximumClockSkewTicks: Int = 1,
        includePrimaryIoPaths: Boolean = true,
    ): TimingReport {
        require(clockPeriodTicks == null || clockPeriodTicks > 0)
        require(maximumClockSkewTicks >= 0)
        val cells = placedCells.associateBy { it.name }
        val sequential = edgeTriggered(netlist)
        val generatedClockPeriod = generatedClockPeriod(netlist, cells)
        val effectiveClockPeriod = clockPeriodTicks ?: generatedClockPeriod
        val clockArrivals = sequential.associateWith { instance ->
            val trigger = edgeTrigger(instance)
            portRouteDelay(routeDelayTicks, cells.getValue(instance.name), trigger.clockPort)
        }

        var criticalPath = 0
        if (includePrimaryIoPaths) netlist.inputs.values.forEach { input ->
            val walk = propagateCombinational(netlist, routeDelayTicks, cells, input)
            netlist.outputs.values.forEach { output ->
                if (walk.reached[output.index]) criticalPath = maxOf(criticalPath, walk.latest[output.index])
            }
        }

        val setupViolations = mutableListOf<SetupViolation>()
        val holdViolations = mutableListOf<HoldViolation>()
        var minimumPeriod = 0
        var worstSetupSlack = Int.MAX_VALUE
        var worstHoldSlack = Int.MAX_VALUE

        sequential.forEach { launch ->
            val launchCell = cells.getValue(launch.name)
            val launchTrigger = edgeTrigger(launch)
            val launchClock = clockArrivals.getValue(launch)
            launch.type.ports.filter { it.direction == PortDirection.OUTPUT }.forEach { outputPort ->
                repeat(outputPort.width) outputBits@{ outputBit ->
                    val clockToQ = launchCell.cell.timing.arcs.filter { arc ->
                        arc.fromPort == launchTrigger.clockPort && arc.toPort == outputPort.name &&
                            (arc.toBit == null || arc.toBit == outputBit)
                    }
                    if (clockToQ.isEmpty()) return@outputBits
                    val output = launch.connections.getValue(outputPort.name)[outputBit]
                    val walk = propagateCombinational(
                        netlist,
                        routeDelayTicks,
                        cells,
                        output,
                        clockToQ.minOf { it.minDelay },
                        clockToQ.maxOf { it.maxDelay },
                    )
                    sequential.forEach captureLoop@{ capture ->
                        val captureCell = cells.getValue(capture.name)
                        val captureClock = clockArrivals.getValue(capture)
                        captureCell.cell.timing.constraints.filterIsInstance<TimingConstraint.SetupHold>()
                            .filter { it.clockEdge == edgeTrigger(capture).edge }
                            .forEach constraintLoop@{ constraint ->
                                val earliestData = portArrival(
                                    routeDelayTicks,
                                    walk,
                                    capture,
                                    captureCell,
                                    constraint.dataPort,
                                    earliest = true,
                                ) ?: return@constraintLoop
                                val latestData = portArrival(
                                    routeDelayTicks,
                                    walk,
                                    capture,
                                    captureCell,
                                    constraint.dataPort,
                                    earliest = false,
                                ) ?: return@constraintLoop
                                val requiredPeriod = maxOf(
                                    0,
                                    launchClock + latestData + constraint.setupTicks - captureClock,
                                )
                                minimumPeriod = maxOf(minimumPeriod, requiredPeriod)
                                effectiveClockPeriod?.let { period ->
                                    val slack = period - requiredPeriod
                                    worstSetupSlack = minOf(worstSetupSlack, slack)
                                    if (slack < 0) {
                                        setupViolations += SetupViolation(
                                            capture.name,
                                            launch.name,
                                            requiredPeriod,
                                            period,
                                            slack,
                                        )
                                    }
                                }

                                val holdSlack = launchClock + earliestData - (captureClock + constraint.holdTicks)
                                worstHoldSlack = minOf(worstHoldSlack, holdSlack)
                                if (holdSlack < 0) {
                                    holdViolations += HoldViolation(
                                        capture.name,
                                        launch.name,
                                        launchClock + earliestData,
                                        captureClock + constraint.holdTicks,
                                        holdSlack,
                                    )
                                }
                            }
                    }
                }
            }
        }

        val transparentLatches = netlist.instances.filter { instance ->
            (instance.type.behavior as? CellBehavior.Stateful)?.trigger == CellBehavior.Trigger.Transparent
        }
        if (transparentLatches.isNotEmpty()) netlist.inputs.forEach { (inputName, inputSignal) ->
            val walk = propagateCombinational(netlist, routeDelayTicks, cells, inputSignal)
            transparentLatches.forEach latchLoop@{ latch ->
                val cell = cells[latch.name] ?: return@latchLoop
                cell.cell.timing.constraints.filterIsInstance<TimingConstraint.SetupHold>().forEach constraintLoop@{ constraint ->
                    val data = portArrival(routeDelayTicks, walk, latch, cell, constraint.dataPort, earliest = true)
                        ?: return@constraintLoop
                    val clock = portArrival(routeDelayTicks, walk, latch, cell, constraint.clockPort, earliest = false)
                        ?: return@constraintLoop
                    val slack = data - (clock + constraint.holdTicks)
                    worstHoldSlack = minOf(worstHoldSlack, slack)
                    if (slack < 0) holdViolations += HoldViolation(latch.name, inputName, data, clock, slack)
                }
            }
        }

        val skewViolations = mutableListOf<ClockSkewViolation>()
        var largestSkew = 0
        sequential.groupBy { instance ->
            val trigger = edgeTrigger(instance)
            instance.connections.getValue(trigger.clockPort).single()
        }.forEach { (clock, sinks) ->
            if (sinks.size < 2) return@forEach
            val ordered = sinks.map { it to clockArrivals.getValue(it) }.sortedBy { it.second }
            val earliest = ordered.first()
            val latest = ordered.last()
            val skew = latest.second - earliest.second
            largestSkew = maxOf(largestSkew, skew)
            if (skew > maximumClockSkewTicks) {
                skewViolations += ClockSkewViolation(
                    clockName(netlist, clock),
                    earliest.first.name,
                    latest.first.name,
                    earliest.second,
                    latest.second,
                    skew,
                    maximumClockSkewTicks,
                )
            }
        }

        return TimingReport(
            criticalPathTicks = criticalPath,
            generatedClockPeriodTicks = generatedClockPeriod,
            minimumClockPeriodTicks = minimumPeriod,
            worstSetupSlackTicks = if (worstSetupSlack == Int.MAX_VALUE) 0 else worstSetupSlack,
            setupViolations = setupViolations,
            worstHoldSlackTicks = if (worstHoldSlack == Int.MAX_VALUE) 0 else worstHoldSlack,
            holdViolations = holdViolations,
            maximumClockSkewTicks = largestSkew,
            clockSkewViolations = skewViolations,
        )
    }

    private fun edgeTriggered(netlist: BooleanNetlist): List<CellInstance> = netlist.instances.filter { instance ->
        (instance.type.behavior as? CellBehavior.Stateful)?.trigger is CellBehavior.Trigger.EdgeTriggered
    }

    private fun edgeTrigger(instance: CellInstance): CellBehavior.Trigger.EdgeTriggered =
        ((instance.type.behavior as CellBehavior.Stateful).trigger as CellBehavior.Trigger.EdgeTriggered)

    private fun propagateCombinational(
        netlist: BooleanNetlist,
        routeDelayTicks: Map<BlockPos, Int>,
        cells: Map<String, PlacedCell>,
        from: Signal,
        initialEarliest: Int = 0,
        initialLatest: Int = 0,
    ): Walk {
        val walk = Walk(netlist.signals)
        walk.reached[from.index] = true
        walk.earliest[from.index] = initialEarliest
        walk.latest[from.index] = initialLatest

        netlist.combinationalOrder().forEach { instance ->
            val cell = cells[instance.name] ?: return@forEach
            instance.type.ports.filter { it.direction == PortDirection.OUTPUT }.forEach { outputPort ->
                repeat(outputPort.width) outputBits@{ outputBit ->
                    val outputSignal = instance.connections.getValue(outputPort.name)[outputBit]
                    var earliest = Int.MAX_VALUE
                    var latest = Int.MIN_VALUE
                    cell.cell.timing.arcs.filter { arc ->
                        arc.toPort == outputPort.name && (arc.toBit == null || arc.toBit == outputBit)
                    }.forEach { arc ->
                        val inputPort = instance.type.port(arc.fromPort)
                        val inputBits = arc.fromBit?.let(::listOf) ?: (0 until inputPort.width).toList()
                        inputBits.forEach { inputBit ->
                            val inputSignal = instance.connections.getValue(inputPort.name)[inputBit]
                            if (!walk.reached[inputSignal.index]) return@forEach
                            val pin = physicalPin(cell, inputPort.name, inputBit)
                            val wire = routeDelayTicks[cell.pin(pin)] ?: 0
                            earliest = minOf(earliest, walk.earliest[inputSignal.index] + wire + arc.minDelay)
                            latest = maxOf(latest, walk.latest[inputSignal.index] + wire + arc.maxDelay)
                        }
                    }
                    if (latest == Int.MIN_VALUE) return@outputBits
                    walk.reached[outputSignal.index] = true
                    walk.earliest[outputSignal.index] = earliest
                    walk.latest[outputSignal.index] = latest
                }
            }
        }
        return walk
    }

    private val TimingArc.minDelay: Int get() = minOf(rise.minTicks, fall.minTicks)
    private val TimingArc.maxDelay: Int get() = maxOf(rise.maxTicks, fall.maxTicks)

    private fun portArrival(
        routeDelayTicks: Map<BlockPos, Int>,
        walk: Walk,
        instance: CellInstance,
        cell: PlacedCell,
        port: String,
        earliest: Boolean,
    ): Int? {
        val arrivals = instance.connections.getValue(port).mapIndexedNotNull { bit, signal ->
            if (!walk.reached[signal.index]) return@mapIndexedNotNull null
            val pin = physicalPin(cell, port, bit)
            val wire = routeDelayTicks[cell.pin(pin)] ?: 0
            (if (earliest) walk.earliest[signal.index] else walk.latest[signal.index]) + wire
        }
        return if (earliest) arrivals.minOrNull() else arrivals.maxOrNull()
    }

    private fun portRouteDelay(routeDelayTicks: Map<BlockPos, Int>, cell: PlacedCell, port: String): Int =
        cell.cell.pins.filter { it.port == port }.maxOf { pin -> routeDelayTicks[cell.pin(pin.name)] ?: 0 }

    private fun physicalPin(cell: PlacedCell, port: String, bit: Int): String =
        cell.cell.pins.single { it.port == port && it.bit == bit }.name

    private fun clockName(netlist: BooleanNetlist, signal: Signal): String =
        netlist.inputs.entries.singleOrNull { it.value == signal }?.key ?: "signal-${signal.index}"

    private fun generatedClockPeriod(netlist: BooleanNetlist, cells: Map<String, PlacedCell>): Int? {
        val periods = netlist.clockSignals.mapNotNull { clock ->
            netlist.instances.firstNotNullOfOrNull { instance ->
                val drivesClock = instance.type.ports.filter { it.direction == PortDirection.OUTPUT }.any { port ->
                    instance.connections.getValue(port.name).any { it == clock }
                }
                if (drivesClock) cells[instance.name]?.cell?.timing?.generatedClockPeriodTicks else null
            }
        }.distinct()
        require(periods.size <= 1) { "multiple generated clock periods are not yet supported: $periods" }
        return periods.singleOrNull()
    }

    private class Walk(signals: Int) {
        val reached: BooleanArray = BooleanArray(signals)
        val earliest: IntArray = IntArray(signals)
        val latest: IntArray = IntArray(signals)
    }
}
