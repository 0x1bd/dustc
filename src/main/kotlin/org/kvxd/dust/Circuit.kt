package org.kvxd.dust

import org.kvxd.dust.netlist.BooleanNetlist

class Circuit internal constructor(
    val name: String,
    val ports: List<CircuitPort>,
    private val netlist: BooleanNetlist,
) {
    val inputs: List<CircuitPort> = ports.filter { it.direction == CircuitPortDirection.INPUT }
    val outputs: List<CircuitPort> = ports.filter { it.direction == CircuitPortDirection.OUTPUT }
    val ioGroups: List<CircuitIoGroup> = ports
        .groupBy { port -> Triple(port.direction, port.ioGroup, port.edge to port.panel) }
        .map { (key, groupedPorts) -> CircuitIoGroup(key.second, key.first, groupedPorts, key.third.first, key.third.second) }

    init {
        require(name.matches(MODULE_NAME)) { "invalid module '$name'" }
        require(ports.map { it.name }.distinct().size == ports.size) { "$name has duplicate ports" }
        ports.filter { it.ioGroup != null }.groupBy { it.ioGroup }.forEach { (group, groupedPorts) ->
            require(groupedPorts.map { it.direction }.distinct().size == 1) {
                "$name uses I/O group '$group' for both inputs and outputs"
            }
            require(groupedPorts.map { it.edge }.distinct().size == 1) {
                "$name gives I/O group '$group' conflicting #[edge] constraints"
            }
            require(groupedPorts.map { it.panel }.distinct().size == 1) {
                "$name gives I/O group '$group' conflicting #[panel] constraints"
            }
        }
    }

    fun lowerToBooleanNetlist(): BooleanNetlist = netlist

    fun evaluate(values: Map<String, ULong>): CircuitResult {
        requireWordSizedPorts()
        require(values.keys == inputs.map { it.name }.toSet()) {
            "expected inputs ${inputs.map { it.name }}, got ${values.keys}"
        }
        val flattened = buildMap {
            inputs.forEach { port ->
                val value = values.getValue(port.name)
                require(port.width == ULong.SIZE_BITS || value < (1uL shl port.width)) {
                    "${port.name} value $value does not fit ${port.width} bits"
                }
                repeat(port.width) { bit -> put(port.bitName(bit), value and (1uL shl bit) != 0uL) }
            }
        }
        val evaluated = netlist.evaluate(flattened)
        return CircuitResult(
            outputs.associate { port ->
                port.name to (0 until port.width).fold(0uL) { value, bit ->
                    if (evaluated.getValue(port.bitName(bit))) value or (1uL shl bit) else value
                }
            },
        )
    }

    fun evaluate(vararg values: Pair<String, ULong>): CircuitResult = evaluate(mapOf(*values))

    fun evaluateAll(vectors: List<Map<String, ULong>>): List<CircuitResult> {
        requireWordSizedPorts()
        val inputNames = inputs.map { it.name }.toSet()
        val results = ArrayList<CircuitResult>(vectors.size)
        vectors.chunked(Long.SIZE_BITS).forEach { batch ->
            val words = netlist.inputs.keys.associateWith { 0L }.toMutableMap()
            batch.forEachIndexed { lane, vector ->
                require(vector.keys == inputNames) { "expected inputs ${inputs.map { it.name }}, got ${vector.keys}" }
                inputs.forEach { port ->
                    val value = vector.getValue(port.name)
                    require(port.width == ULong.SIZE_BITS || value < (1uL shl port.width)) {
                        "${port.name} value $value does not fit ${port.width} bits"
                    }
                    repeat(port.width) { bit ->
                        if (value and (1uL shl bit) != 0uL) {
                            val name = port.bitName(bit)
                            words[name] = words.getValue(name) or (1L shl lane)
                        }
                    }
                }
            }
            val evaluated = netlist.evaluateWords(words)
            batch.indices.forEach { lane ->
                results += CircuitResult(
                    outputs.associate { port ->
                        port.name to (0 until port.width).fold(0uL) { value, bit ->
                            if (evaluated.getValue(port.bitName(bit)) and (1L shl lane) != 0L) {
                                value or (1uL shl bit)
                            } else {
                                value
                            }
                        }
                    },
                )
            }
        }
        return results
    }

    private fun requireWordSizedPorts() {
        val wider = ports.firstOrNull { it.width > ULong.SIZE_BITS } ?: return
        throw IllegalArgumentException(
            "word evaluation supports ports up to ${ULong.SIZE_BITS} bits; ${wider.name} is ${wider.width} bits"
        )
    }
}
