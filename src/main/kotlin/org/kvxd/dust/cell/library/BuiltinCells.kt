package org.kvxd.dust.cell.library

import org.kvxd.dust.cell.behavior.CellBehavior
import org.kvxd.dust.cell.behavior.CellEvaluation
import org.kvxd.dust.cell.definition.CellPort
import org.kvxd.dust.cell.definition.CellType
import org.kvxd.dust.cell.definition.CellTypeId
import org.kvxd.dust.cell.definition.PortDirection
import org.kvxd.dust.cell.timing.CellTiming
import org.kvxd.dust.cell.timing.DelayRange
import org.kvxd.dust.cell.timing.Edge
import org.kvxd.dust.cell.timing.TimingArc
import org.kvxd.dust.cell.timing.TimingConstraint

object BuiltinCells {
    val not: CellType = combinational("not", listOf(input("a")), output("y"), 1) { !it.single() }
    val and2: CellType = combinational("and2", listOf(input("a"), input("b")), output("y"), 2) {
        it[0] && it[1]
    }
    val or2: CellType = combinational("or2", listOf(input("a"), input("b")), output("y"), 1) {
        it[0] || it[1]
    }
    val xor2: CellType = combinational("xor2", listOf(input("a"), input("b")), output("y"), 4) {
        it[0] xor it[1]
    }
    val mux2: CellType = CellType(
        CellTypeId("mux2"),
        listOf(input("select"), input("low"), input("high"), output("y")),
        CellBehavior.Combinational { values ->
            val select = values.getValue("select").single()
            val value = if (select) values.getValue("high").single() else values.getValue("low").single()
            mapOf("y" to booleanArrayOf(value))
        },
        CellTiming(
            listOf(
                arc("select", "y", 7),
                arc("low", "y", 5),
                arc("high", "y", 6),
            ),
        ),
    )
    val latch: CellType = CellType(
        CellTypeId("latch"),
        listOf(input("d"), input("hold"), output("q")),
        CellBehavior.Stateful(1, CellBehavior.StateMode.TRANSPARENT) { inputs, previous ->
            val q = if (inputs.getValue("hold").single()) previous.single() else inputs.getValue("d").single()
            CellEvaluation(mapOf("q" to booleanArrayOf(q)), booleanArrayOf(q))
        },
        CellTiming(
            arcs = listOf(
                arc("d", "q", 1),
                arc("hold", "q", 1),
            ),
            constraints = listOf(TimingConstraint.SetupHold("d", "hold", Edge.RISE, 0, 1)),
        ),
    )
    val inputPad: CellType = source("input-pad")
    val outputPad: CellType = sink("output-pad")
    val inputTerminal: CellType = source("input-terminal")
    val outputTerminal: CellType = sink("output-terminal")

    val byId: Map<CellTypeId, CellType> = listOf(
        not,
        and2,
        or2,
        xor2,
        mux2,
        latch,
        inputPad,
        outputPad,
        inputTerminal,
        outputTerminal,
    ).associateBy { it.id }

    private fun input(name: String): CellPort = CellPort(name, 1, PortDirection.INPUT)
    private fun output(name: String): CellPort = CellPort(name, 1, PortDirection.OUTPUT)
    private fun arc(from: String, to: String, ticks: Int): TimingArc = TimingArc(
        from,
        to,
        rise = DelayRange(ticks, ticks),
        fall = DelayRange(ticks, ticks),
    )

    private fun source(id: String): CellType = CellType(
        CellTypeId(id),
        listOf(output("y")),
        CellBehavior.Combinational { mapOf("y" to booleanArrayOf(false)) },
        CellTiming.NONE,
    )

    private fun sink(id: String): CellType = CellType(
        CellTypeId(id),
        listOf(input("a")),
        CellBehavior.Combinational { emptyMap() },
        CellTiming.NONE,
    )

    private fun combinational(
        id: String,
        inputs: List<CellPort>,
        output: CellPort,
        ticks: Int,
        operation: (List<Boolean>) -> Boolean,
    ): CellType = CellType(
        CellTypeId(id),
        inputs + output,
        CellBehavior.Combinational { values ->
            mapOf(output.name to booleanArrayOf(operation(inputs.map { values.getValue(it.name).single() })))
        },
        CellTiming(inputs.map { arc(it.name, output.name, ticks) }),
    )
}
