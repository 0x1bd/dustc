package org.kvxd.dust.technology.definition

import org.kvxd.dust.cell.library.CellParameter
import org.kvxd.dust.cell.timing.CellTiming
import org.kvxd.dust.cell.timing.DelayRange
import org.kvxd.dust.cell.timing.Edge
import org.kvxd.dust.cell.timing.TimingArc
import org.kvxd.dust.cell.timing.TimingConstraint
import org.kvxd.dust.device.block.ContainerBlockEntity
import org.kvxd.dust.device.block.ItemStack
import org.kvxd.dust.device.block.SignBlockEntity
import org.kvxd.dust.device.geometry.BlockPos
import org.kvxd.dust.netlist.InterfaceEdge
import org.kvxd.dust.technology.PinDirection

internal object CellDefinitionParser {
    fun parameters(sourceName: String, text: String): List<CellParameter> {
        val lines = sourceLines(sourceName, text)
        val (_, headerParameters) = declaration(lines.first())
        val sectionParameters = sectionLines(lines.drop(1))["parameters"].orEmpty().map { parameter(it, emptyMap()) }
        return (headerParameters + sectionParameters).also { parameters ->
            require(parameters.map { it.name }.distinct().size == parameters.size) {
                "$sourceName repeats a cell parameter"
            }
            validateOptionalOrder(parameters, sourceName)
        }
    }

    fun parse(sourceName: String, text: String, arguments: Map<String, Int> = emptyMap()): CellDefinition {
        val lines = sourceLines(sourceName, text)
        val (name, headerParameters) = declaration(lines.first())
        val sections = sectionLines(lines.drop(1))
        val sectionParameters = sections["parameters"].orEmpty().map { parameter(it, emptyMap()) }
        val parameters = headerParameters + sectionParameters
        require(parameters.map { it.name }.distinct().size == parameters.size) {
            "$sourceName repeats a cell parameter"
        }
        validateOptionalOrder(parameters, sourceName)
        val values = bind(parameters, arguments, lines.first().location)

        val palette = expanded(sections["palette"].orEmpty(), values).map { line ->
            val parts = line.text.split(Regex("\\s*=\\s*"), limit = 2)
            if (parts.size != 2 || parts[0].length != 1) line.location.error("invalid palette entry '${line.text}'")
            CellPaletteEntry(parts[0].single(), interpolate(parts[1], line.variables, line.location))
        }
        val pins = expanded(sections["pins"].orEmpty(), values).map(::pin)
        val layers = layers(sections["layers"].orEmpty(), values)
        val layout = expanded(sections["layout"].orEmpty(), values).map(::layout)
        val entities = expanded(
            sections["block-entities"].orEmpty() + sections["block_entities"].orEmpty(),
            values,
        ).map(::blockEntity)
        val observations = expanded(sections["observations"].orEmpty(), values).map(::observation)
        val timingLines = expanded(sections["timing"].orEmpty(), values)
        val placementLines = expanded(sections["placement"].orEmpty(), values)
        val sourceLocations = buildMap {
            pins.forEachIndexed { index, _ -> put("pin:$index", expanded(sections["pins"].orEmpty(), values)[index].location) }
            layout.forEachIndexed { index, _ -> put("layout:$index", expanded(sections["layout"].orEmpty(), values)[index].location) }
            entities.forEachIndexed { index, _ ->
                val source = sections["block-entities"].orEmpty() + sections["block_entities"].orEmpty()
                put("block-entity:$index", expanded(source, values)[index].location)
            }
            observations.forEachIndexed { index, _ ->
                put("observation:$index", expanded(sections["observations"].orEmpty(), values)[index].location)
            }
            put("definition", lines.first().location)
        }
        return CellDefinition(
            name = name,
            parameters = parameters,
            arguments = values,
            palette = palette,
            pins = pins,
            layers = layers,
            layout = layout,
            blockEntities = entities,
            observations = observations,
            timing = timing(timingLines),
            placement = placement(placementLines),
            sourceLocations = sourceLocations,
        )
    }

    fun parse(sourceName: String, text: String, arguments: List<Int>): CellDefinition {
        val parameters = parameters(sourceName, text)
        require(arguments.size <= parameters.size) {
            "$sourceName accepts ${parameters.size} cell parameters, got ${arguments.size}"
        }
        return parse(sourceName, text, parameters.mapIndexedNotNull { index, parameter ->
            arguments.getOrNull(index)?.let { parameter.name to it }
        }.toMap())
    }

    private fun sourceLines(sourceName: String, text: String): List<SourceLine> = text.lineSequence()
        .mapIndexedNotNull { index, raw ->
            val content = stripComment(raw).trim()
            content.takeIf(String::isNotEmpty)?.let { SourceLine(it, CellSourceLocation(sourceName, index + 1)) }
        }
        .toList()
        .also { require(it.isNotEmpty()) { "$sourceName is empty" } }

    private fun stripComment(text: String): String {
        var quoted = false
        var escaped = false
        for (index in 0 until text.lastIndex) {
            val character = text[index]
            if (escaped) escaped = false else when (character) {
                '\\' -> if (quoted) escaped = true
                '"' -> quoted = !quoted
                '/' -> if (!quoted && text[index + 1] == '/') return text.substring(0, index)
            }
        }
        return text
    }

    private fun declaration(line: SourceLine): Pair<String, List<CellParameter>> {
        val match = Regex("cell\\s+([a-z][a-z0-9-]*)(?:\\s*<(.+)>)?").matchEntire(line.text)
            ?: line.location.error("definition must start with 'cell <name>'")
        val parameters = match.groupValues[2].takeIf(String::isNotBlank)
            ?.let(::splitTopLevel)
            .orEmpty()
            .map { parameter(SourceLine(it, line.location), emptyMap()) }
        return match.groupValues[1] to parameters
    }

    private fun sectionLines(lines: List<SourceLine>): Map<String, List<SourceLine>> {
        val sections = linkedMapOf<String, MutableList<SourceLine>>()
        var current: String? = null
        lines.forEach { line ->
            val header = line.text.removeSuffix(":")
            if (line.text.endsWith(':') && header in SECTIONS) {
                current = header
            } else {
                val section = current ?: line.location.error("content outside a section: ${line.text}")
                sections.getOrPut(section) { mutableListOf() } += line
            }
        }
        return sections
    }

    private fun parameter(line: SourceLine, variables: Map<String, Int>): CellParameter {
        var body = line.text.removePrefix("const ").trim()
        val name = Regex("[A-Za-z_][A-Za-z0-9_]*").find(body)?.takeIf { it.range.first == 0 }?.value
            ?: line.location.error("invalid cell parameter '${line.text}'")
        body = body.substring(name.length).trim().removePrefix(":").trim().removePrefix("int").trim()
        body = body.removePrefix("in ").removePrefix("range=").trim()
        val rangeOperator = if ("..=" in body) "..=" else ".."
        val rangeIndex = body.indexOf(rangeOperator)
        if (rangeIndex < 0) line.location.error("parameter $name needs an inclusive integer range")
        val lowerText = body.substring(0, rangeIndex).trim()
        val remainder = body.substring(rangeIndex + rangeOperator.length).trim()
        val defaultMarker = Regex("\\s+(?:default\\s*=|=)\\s*").find(remainder)
        val upperText = if (defaultMarker == null) remainder else remainder.substring(0, defaultMarker.range.first).trim()
        val defaultText = defaultMarker?.let { remainder.substring(it.range.last + 1).trim() }
        val lower = integer(lowerText, variables, line.location)
        val upper = integer(upperText, variables, line.location)
        if (lower > upper) line.location.error("parameter $name has an empty range $lower..$upper")
        val default = defaultText?.let { integer(it, variables, line.location) }
        return runCatching { CellParameter(name, lower..upper, default) }
            .getOrElse { line.location.error(it.message ?: "invalid parameter $name") }
    }

    private fun validateOptionalOrder(parameters: List<CellParameter>, sourceName: String) {
        val firstDefault = parameters.indexOfFirst { it.default != null }
        require(firstDefault < 0 || parameters.drop(firstDefault).all { it.default != null }) {
            "$sourceName has a required parameter after an optional parameter"
        }
    }

    private fun bind(
        parameters: List<CellParameter>,
        supplied: Map<String, Int>,
        location: CellSourceLocation,
    ): Map<String, Int> {
        val known = parameters.mapTo(linkedSetOf()) { it.name }
        val unknown = supplied.keys - known
        if (unknown.isNotEmpty()) location.error("unknown cell parameters $unknown")
        return parameters.associate { parameter ->
            val value = supplied[parameter.name] ?: parameter.default
                ?: location.error("cell parameter '${parameter.name}' is required")
            if (value !in parameter.range) {
                location.error("cell parameter '${parameter.name}' must be in ${parameter.range}, got $value")
            }
            parameter.name to value
        }
    }

    private fun expanded(lines: List<SourceLine>, variables: Map<String, Int>): List<ExpandedLine> =
        expandRange(lines, 0, lines.size, variables)

    private fun expandRange(
        lines: List<SourceLine>,
        start: Int,
        end: Int,
        variables: Map<String, Int>,
    ): List<ExpandedLine> = buildList {
        var index = start
        while (index < end) {
            val line = lines[index]
            if (line.text == "}") line.location.error("unexpected '}'")
            val loop = Regex("for\\s+([A-Za-z_][A-Za-z0-9_]*)\\s+in\\s+(.+)\\s*\\{").matchEntire(line.text)
            if (loop == null) {
                add(ExpandedLine(line.text, line.location, variables))
                index++
                continue
            }
            val close = matchingBrace(lines, index, end)
            val iterator = loop.groupValues[1]
            if (iterator in variables) line.location.error("loop variable '$iterator' shadows an existing integer")
            val range = parseLoopRange(loop.groupValues[2], variables, line.location)
            range.forEach { value -> addAll(expandRange(lines, index + 1, close, variables + (iterator to value))) }
            index = close + 1
        }
    }

    private fun matchingBrace(lines: List<SourceLine>, opening: Int, end: Int): Int {
        var depth = 1
        for (index in opening + 1 until end) {
            if (lines[index].text.endsWith('{')) depth++
            if (lines[index].text == "}") depth--
            if (depth == 0) return index
        }
        lines[opening].location.error("unterminated for loop")
    }

    private fun parseLoopRange(text: String, variables: Map<String, Int>, location: CellSourceLocation): IntRange {
        val inclusive = "..=" in text
        val operator = if (inclusive) "..=" else ".."
        val parts = text.split(operator, limit = 2)
        if (parts.size != 2) location.error("invalid loop range '$text'")
        val first = integer(parts[0], variables, location)
        val bound = integer(parts[1], variables, location)
        if (first > bound || (!inclusive && first == bound)) return IntRange.EMPTY
        return first..if (inclusive) bound else Math.subtractExact(bound, 1)
    }

    private fun pin(line: ExpandedLine): CellPinDefinition {
        val parts = splitWords(line.text)
        if (parts.size < 3) line.location.error("invalid pin '${line.text}'")
        val options = options(parts.drop(3), line)
        val known = setOf("abut", "south", "branch", "drive", "required")
        if (options.keys.any { it !in known }) line.location.error("unknown pin option in '${line.text}'")
        return CellPinDefinition(
            name = indexedName(parts[0], line),
            direction = runCatching { PinDirection.valueOf(parts[1].uppercase()) }
                .getOrElse { line.location.error("invalid pin direction '${parts[1]}'") },
            position = position(parts[2], line),
            allowsHorizontalAbutment = booleanOption(options, "abut", true, line),
            accessesFromSouth = booleanOption(options, "south", false, line),
            branchOffsetX = integerOption(options, "branch", 0, line),
            driveStrength = integerOption(options, "drive", 15, line),
            requiredStrength = integerOption(options, "required", 1, line),
        )
    }

    private fun layers(lines: List<SourceLine>, variables: Map<String, Int>): List<CellLayerDefinition> {
        val result = mutableListOf<CellLayerDefinition>()
        var y: Int? = null
        var rows = mutableListOf<String>()
        fun finish() {
            y?.let { result += CellLayerDefinition(it, rows.toList()) }
            y = null
            rows = mutableListOf()
        }
        expanded(lines, variables).forEach { line ->
            if (line.text.startsWith('@')) {
                finish()
                y = integer(line.text.drop(1), line.variables, line.location)
            } else {
                if (y == null) line.location.error("layer row without a y coordinate")
                rows += line.text
            }
        }
        finish()
        return result
    }

    private fun layout(line: ExpandedLine): CellLayoutEntry {
        val parts = splitWords(line.text)
        if (parts.size != 3) line.location.error("invalid layout entry '${line.text}'")
        return when (parts[0]) {
            "include" -> {
                val (cell, arguments) = specialization(parts[1], line)
                CellLayoutEntry.Include(cell, arguments, position(parts[2], line))
            }
            "wire" -> CellLayoutEntry.Wire(interpolate(parts[1], line.variables, line.location), position(parts[2], line))
            "block", "place" -> {
                if (parts[1].length != 1) line.location.error("layout block needs one palette symbol")
                CellLayoutEntry.Block(parts[1].single(), position(parts[2], line))
            }
            else -> line.location.error("unknown layout directive '${parts[0]}'")
        }
    }

    private fun specialization(text: String, line: ExpandedLine): Pair<String, List<Int>> {
        val match = Regex("([a-z][a-z0-9-]*)(?:<(.+)>)?").matchEntire(text)
            ?: line.location.error("invalid included cell '$text'")
        val arguments = match.groupValues[2].takeIf(String::isNotBlank)?.let(::splitTopLevel).orEmpty()
            .map { integer(it, line.variables, line.location) }
        return match.groupValues[1] to arguments
    }

    private fun blockEntity(line: ExpandedLine): CellBlockEntityDefinition {
        val parts = splitWords(line.text).toMutableList()
        if (parts.firstOrNull() == "entity") parts.removeAt(0)
        if (parts.size < 2) line.location.error("invalid block-entity directive '${line.text}'")
        val kind = parts[0]
        val at = position(parts[1], line)
        val options = options(parts.drop(2), line)
        val entity = when (kind) {
            "barrel", "container" -> {
                val itemId = options["item"] ?: "minecraft:redstone"
                val signal = options["signal"]?.let { integer(it, line.variables, line.location) }
                if (signal != null) {
                    ContainerBlockEntity.barrelSignal(signal, itemId)
                } else {
                    val slots = options["slots"]?.let { integer(it, line.variables, line.location) }
                        ?: ContainerBlockEntity.BARREL_SLOTS
                    val items = options["items"]?.let { parseItems(it, line) }.orEmpty()
                    ContainerBlockEntity(slots, items)
                }
            }
            "sign" -> SignBlockEntity(
                lines = unquote(options["text"].orEmpty()).split('|').takeUnless { it == listOf("") }.orEmpty(),
                color = options["color"] ?: "black",
                glowing = options["glowing"]?.toBooleanStrictOrNull()
                    ?: if ("glowing" in options) line.location.error("glowing must be true or false") else false,
            )
            else -> line.location.error("unsupported block entity '$kind'")
        }
        return CellBlockEntityDefinition(at, entity)
    }

    private fun parseItems(text: String, line: ExpandedLine): List<ItemStack> = unquote(text)
        .takeIf(String::isNotBlank)
        ?.split(';')
        ?.map { entry ->
            val match = Regex("(\\d+)@([^*]+)\\*(\\d+)").matchEntire(entry)
                ?: line.location.error("invalid container item '$entry'; expected SLOT@ITEM*COUNT")
            ItemStack(match.groupValues[1].toInt(), match.groupValues[2], match.groupValues[3].toInt())
        }.orEmpty()

    private fun observation(line: ExpandedLine): CellObservationDefinition {
        val parts = splitWords(line.text).let { if (it.firstOrNull() == "observe") it.drop(1) else it }
        if (parts.size != 2) line.location.error("invalid observation '${line.text}'")
        return CellObservationDefinition(indexedName(parts[0], line), position(parts[1], line))
    }

    private fun timing(lines: List<ExpandedLine>): CellTiming? {
        if (lines.isEmpty()) return null
        val arcs = mutableListOf<TimingArc>()
        val constraints = mutableListOf<TimingConstraint>()
        lines.forEach { line ->
            val parts = splitWords(line.text)
            when (parts.firstOrNull()) {
                "arc" -> {
                    val arrow = parts.indexOf("->")
                    if (arrow != 2 || parts.size < 5) line.location.error("invalid timing arc '${line.text}'")
                    val from = portReference(parts[1], line)
                    val to = portReference(parts[3], line)
                    val options = options(parts.drop(4), line)
                    val rise = delay(options["rise"] ?: line.location.error("timing arc needs rise="), line)
                    val fall = delay(options["fall"] ?: line.location.error("timing arc needs fall="), line)
                    arcs += TimingArc(from.first, to.first, from.second, to.second, rise, fall)
                }
                "setup-hold", "setup_hold" -> {
                    if (parts.size < 4) line.location.error("invalid setup-hold constraint '${line.text}'")
                    val options = options(parts.drop(3), line)
                    constraints += TimingConstraint.SetupHold(
                        dataPort = parts[1],
                        clockPort = parts[2],
                        clockEdge = runCatching { Edge.valueOf(options["edge"].orEmpty().uppercase()) }
                            .getOrElse { line.location.error("setup-hold edge must be rise or fall") },
                        setupTicks = integer(options["setup"].orEmpty(), line.variables, line.location),
                        holdTicks = integer(options["hold"].orEmpty(), line.variables, line.location),
                    )
                }
                else -> line.location.error("unknown timing directive '${parts.firstOrNull().orEmpty()}'")
            }
        }
        return CellTiming(arcs, constraints)
    }

    private fun delay(text: String, line: ExpandedLine): DelayRange {
        val parts = text.split(if ("..=" in text) "..=" else "..", limit = 2)
        val minimum = integer(parts[0], line.variables, line.location)
        val maximum = if (parts.size == 1) minimum else integer(parts[1], line.variables, line.location)
        return runCatching { DelayRange(minimum, maximum) }
            .getOrElse { line.location.error(it.message ?: "invalid delay range") }
    }

    private fun placement(lines: List<ExpandedLine>): CellPlacementDefinition? {
        if (lines.isEmpty()) return null
        val combined = lines.flatMap { line -> splitWords(line.text).map { it to line } }
        var exclusive = true
        var visible: InterfaceEdge? = InterfaceEdge.NORTH
        combined.forEach { (option, line) ->
            val key = option.substringBefore('=').replace('_', '-')
            val value = option.substringAfter('=', "")
            when (key) {
                "exclusive-row" -> exclusive = value.toBooleanStrictOrNull()
                    ?: line.location.error("exclusive-row must be true or false")
                "visible-edge" -> visible = if (value == "none") null else runCatching {
                    InterfaceEdge.valueOf(value.uppercase())
                }.getOrElse { line.location.error("invalid visible edge '$value'") }
                else -> line.location.error("unknown placement option '$key'")
            }
        }
        return CellPlacementDefinition(exclusive, visible)
    }

    private fun portReference(text: String, line: ExpandedLine): Pair<String, Int?> {
        val match = Regex("([A-Za-z_][A-Za-z0-9_-]*)(?:\\[(.+)])?").matchEntire(text)
            ?: line.location.error("invalid port reference '$text'")
        return match.groupValues[1] to match.groupValues[2].takeIf(String::isNotBlank)?.let {
            integer(it, line.variables, line.location)
        }
    }

    private fun indexedName(text: String, line: ExpandedLine): String {
        val baseEnd = text.indexOf('[').let { if (it < 0) text.length else it }
        val base = text.substring(0, baseEnd)
        if (!base.matches(Regex("[A-Za-z_][A-Za-z0-9_-]*"))) line.location.error("invalid name '$text'")
        val suffix = text.substring(baseEnd)
        if (suffix.isEmpty()) return base
        val result = StringBuilder(base)
        var cursor = 0
        while (cursor < suffix.length) {
            if (suffix[cursor] != '[') line.location.error("invalid indexed name '$text'")
            val close = suffix.indexOf(']', cursor + 1)
            if (close < 0) line.location.error("invalid indexed name '$text'")
            val indices = splitTopLevel(suffix.substring(cursor + 1, close))
            if (indices.isEmpty()) line.location.error("empty index in '$text'")
            result.append('[').append(indices.joinToString(",") { integer(it, line.variables, line.location).toString() }).append(']')
            cursor = close + 1
        }
        return result.toString()
    }

    private fun position(text: String, line: ExpandedLine): BlockPos {
        if (!text.startsWith('@')) line.location.error("invalid position '$text'")
        val values = splitTopLevel(text.drop(1))
        if (values.size != 3) line.location.error("invalid position '$text'")
        return BlockPos(
            integer(values[0], line.variables, line.location),
            integer(values[1], line.variables, line.location),
            integer(values[2], line.variables, line.location),
        )
    }

    private fun options(parts: List<String>, line: ExpandedLine): Map<String, String> = parts.associate { option ->
        if ('=' !in option) line.location.error("invalid option '$option'")
        option.substringBefore('=') to option.substringAfter('=')
    }

    private fun booleanOption(
        options: Map<String, String>,
        name: String,
        default: Boolean,
        line: ExpandedLine,
    ): Boolean = options[name]?.toBooleanStrictOrNull()
        ?: if (name in options) line.location.error("$name must be true or false") else default

    private fun integerOption(options: Map<String, String>, name: String, default: Int, line: ExpandedLine): Int =
        options[name]?.let { integer(it, line.variables, line.location) } ?: default

    private fun integer(text: String, variables: Map<String, Int>, location: CellSourceLocation): Int =
        CellIntegerExpression.evaluate(text, variables, location)

    private fun interpolate(text: String, variables: Map<String, Int>, location: CellSourceLocation): String =
        Regex("\\$\\{([^}]+)}").replace(text) { match -> integer(match.groupValues[1], variables, location).toString() }

    private fun unquote(text: String): String {
        if (text.length < 2 || text.first() != '"' || text.last() != '"') return text
        return buildString {
            var escaped = false
            text.substring(1, text.lastIndex).forEach { character ->
                if (escaped) {
                    append(when (character) { 'n' -> '\n'; 't' -> '\t'; else -> character })
                    escaped = false
                } else if (character == '\\') escaped = true else append(character)
            }
            require(!escaped) { "unterminated escape" }
        }
    }

    private fun splitWords(text: String): List<String> = splitRespecting(text, whitespace = true)

    private fun splitTopLevel(text: String): List<String> = splitRespecting(text, whitespace = false)

    private fun splitRespecting(text: String, whitespace: Boolean): List<String> {
        val result = mutableListOf<String>()
        var start = 0
        var round = 0
        var square = 0
        var angle = 0
        var topLevelCommas = 0
        var quoted = false
        var escaped = false
        fun separator(character: Char): Boolean = if (whitespace) character.isWhitespace() else character == ','
        text.forEachIndexed { index, character ->
            if (escaped) {
                escaped = false
            } else if (quoted && character == '\\') {
                escaped = true
            } else if (character == '"') {
                quoted = !quoted
            } else if (!quoted) {
                when (character) {
                    '(' -> round++
                    ')' -> round--
                    '[' -> square++
                    ']' -> square--
                    '<' -> angle++
                    '>' -> if (angle > 0) angle--
                    ',' -> if (round == 0 && square == 0 && angle == 0) topLevelCommas++
                }
                val currentToken = text.substring(start, index)
                val insidePosition = whitespace && currentToken.trimStart().startsWith('@') &&
                    (topLevelCommas < 2 || currentToken.substringAfterLast(',').isBlank())
                if (separator(character) && round == 0 && square == 0 && angle == 0 && !insidePosition) {
                    text.substring(start, index).trim().takeIf(String::isNotEmpty)?.let(result::add)
                    start = index + 1
                    topLevelCommas = 0
                }
            }
        }
        text.substring(start).trim().takeIf(String::isNotEmpty)?.let(result::add)
        return result
    }

    private data class SourceLine(val text: String, val location: CellSourceLocation)

    private data class ExpandedLine(
        val text: String,
        val location: CellSourceLocation,
        val variables: Map<String, Int>,
    )

    private val SECTIONS = setOf(
        "parameters",
        "palette",
        "pins",
        "layers",
        "layout",
        "block-entities",
        "block_entities",
        "observations",
        "timing",
        "placement",
    )
}
