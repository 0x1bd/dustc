package org.kvxd.dust.netlist

import org.kvxd.dust.cell.definition.CellType
import org.kvxd.dust.cell.library.BuiltinCells

enum class Primitive(val cellType: CellType) {
    NOT(BuiltinCells.not),
    AND2(BuiltinCells.and2),
    OR2(BuiltinCells.or2),
    XOR2(BuiltinCells.xor2),
    MUX2(BuiltinCells.mux2),

    LATCH(BuiltinCells.latch),
    DFF(BuiltinCells.dff),
}
