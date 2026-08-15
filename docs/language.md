# Dust language

Dust is a small hardware description language for building Boolean circuits that `dustc` can place and route as
Minecraft redstone.

## A first module

```dust
module main(
    input controls {
        a: bit,
    },
    output result {
        y: bit,
    },
) {
    y = ~a
}
```

Every module has a name, a list of inputs and outputs, and a body. A file may contain multiple modules. When building a
schematic, `dustc` uses the module named `main` as the top level. The `--module <name>` option can build older sources
whose top-level module has another name.

Module, port, and I/O-group names use lower-case letters, digits, and `_`, and must start with a letter.

## Module parameters

Modules may declare compile-time integer parameters before their ports:

```dust
module inverter<const WIDTH: int = 8>(
    input a: bits<WIDTH>,
    output y: bits<WIDTH>,
) {
    for i in 0..WIDTH { y[i] = ~a[i] }
}
```

Parameters are positional in specialized calls, and trailing parameters may have defaults. Required parameters must
precede parameters with defaults. A specialization is written as `inverter<16>(a)`. Parameters can be used in bus
widths, loop bounds, constants, and further specialization calls.

## Signals and buses

A `bit` is one Boolean signal:

```dust
input enable: bit
output active: bit
```

A `bits<N>` value is a bus containing `N` signals. `N` may be a compile-time integer expression:

```dust
input value: bits<8>
output result: bits<16>
```

Bus widths are between 1 and 4096 bits. Bit 0 is the least-significant bit when a bus is interpreted as an integer.
Integer-valued helpers such as `const_bits` and the word-evaluation API remain limited to 64-bit values. Wider buses
are intended for structural state such as display framebuffers.

Index a bus with `[]`, or select a half-open range with `[..]`:

```dust
let low_bit = value[0]
result[3] = value[5]
let low_nibble = value[0..4]
result[4..8] = value[0..4]
```

Indices and slice bounds must be compile-time integers. A slice `value[first..end]` contains bits `first` through
`end - 1`, preserving least-significant-bit-first order. Bounds must select at least one bit and remain within the bus.

A persistent physical display is declared as a top-level output type:

```dust
output screen: display<8, 8>
```

Unlike `bit` and `bits<N>`, a display output is a physical sink. Assign it a pixel
write command with `display_write(x, y, pixel_value, plot, plot_all)`. Display outputs are automatically placed with
their lamp face on the north exterior edge of the schematic.

## I/O groups

Related ports can be collected into named groups:

```dust
module adder(
    input operands {
        a: bits<4>,
        b: bits<4>,
        cin: bit,
    },
    output result {
        sum: bits<4>,
        cout: bit,
    },
) {
    // ...
}
```

Groups are primarily interface and placement metadata. They do **not** create a value that can be read from expressions,
and grouping ports by itself does not force them into a physical strip or panel.

A group name can, however, be referenced by placement attributes such as `#[near(operands)]`.

## Logic expressions

Dust has four primitive Boolean operators:

```dust
~a
!a

a & b
a and b

a ^ b

a | b
a or b
```

`~` and `!` are NOT, `&`/`and` are AND, `^` is XOR, and `|`/`or` are OR.

Precedence from highest to lowest is:

```text
~ !
& and
^
| or
```

Use parentheses whenever the intended grouping is not obvious:

```dust
let carry = (a & b) | (p & cin)
```

Operators work element-by-element on equal-width buses as well as single bits:

```dust
module xor8(
    input a: bits<8>,
    input b: bits<8>,
    output y: bits<8>,
) {
    y = a ^ b
}
```

The two operands of a binary gate must have the same width.

## Local values

Use `let` to name an intermediate value:

```dust
let p = a ^ b
let carry = (a & b) | (p & cin)
```

A normal `let` binding is immutable.

`let mut` creates a binding that may be rebound while the compiler elaborates the module:

```dust
let mut carry = cin
carry = next_carry
```

This is useful for constructing chains in loops, but it is important that `let mut` **does not create storage hardware
**. It only changes which compile-time value the name refers to. Use `latch()` when the circuit actually needs state.

Mutable buses can also be changed one bit or slice at a time:

```dust
let mut value = a
value[2] = b
value[4..8] = replacement
```

`let rec` predeclares a register result so its next-state expression can read the current state:

```dust
let rec count = resettable_register<8>(increment<8>(count).result, clear, clock)
```

Recursive bindings must be initialized directly by `dff`, `register`, `enabled_register`, or `resettable_register`.
Bus registers require an explicit width parameter because the binding's width must be known before its initializer is
elaborated.

## Outputs

Assign outputs directly:

```dust
y = a ^ b
sum[0] = p
```

Every output bit must be assigned exactly once. Dust rejects missing assignments and multiple assignments to the same
output bit.

Outputs cannot currently be read back while a module is being built. If a result is needed again, give the expression a
local name first:

```dust
let p = a ^ b
sum = p
carry = p & cin
```

## Compile-time integers

Integer values exist only while elaborating the circuit. They are used for things such as loop bounds and bus indices;
they are not wires and cannot be connected to gates.

Dust accepts decimal, hexadecimal, and binary integer literals, with optional `_` separators:

```dust
0
12
0xff
0b1010
1_000
```

For example:

```dust
let index = 3
out = bus[index]
```

Checked compile-time arithmetic supports unary `+` and `-`, and binary `+`, `-`, `*`, `/`, and `%`. Multiplication,
division, and remainder bind more tightly than addition and subtraction. Overflow, division by zero, and values outside
the valid 1-to-64-bit bus-width range are errors.

`clog2(value)` returns the ceiling of the base-two logarithm of a positive integer. For example, `clog2(13)` is 4.

## Signal constants

`true` and `false` are one-bit hardware constants. `const_bits<N>(value)` creates an `N`-bit,
least-significant-bit-first
constant and rejects values that do not fit:

```dust
let zero = false
let mask = const_bits<8>(0b1010_0101)
```

All constant bits in one top-level circuit share one physical low source and one physical high source.

## Loops

`for` loops generate repeated hardware at compile time:

```dust
for i in 0..8 {
    y[i] = a[i] ^ b[i]
}
```

`0..8` produces `0` through `7`. An inclusive range can be written as:

```dust
for i in 0..=7 {
    // i is 0 through 7
}
```

Ranges must count upward and both bounds must be known at compile time.

The loop body is elaborated once for every value of the loop index. It is not a runtime loop in the generated redstone.

A ripple-carry adder is a good example:

```dust
let mut carry = cin
for i in 0..4 {
    let p = a[i] ^ b[i]
    sum[i] = p ^ carry
    carry = (a[i] & b[i]) | (p & carry)
}
cout = carry
```

## Hardware conditionals

An `if` expression selects between two hardware values:

```dust
y = if select { high } else { low }
```

The condition must be one bit. Both branches must have the same width and may be either bits or buses. The compiler
builds both branch expressions and lowers each result bit to a multiplexer (`mux`). An `else` branch is required.

Conditionals may nest and may appear anywhere another signal expression is accepted:

```dust
let selected = if first { a } else { if second { b } else { c } }
```

## Built-in hardware

### Multiplexer

`mux(select, low, high)` selects between two equal-width values:

```dust
y = mux(select, a, b)
```

When `select` is 0, the result is `low`. When it is 1, the result is `high`.

`select` must be one bit. `low` and `high` may be either bits or buses, but they must have the same width.

```dust
module mux8(
    input select: bit,
    input low: bits<8>,
    input high: bits<8>,
    output y: bits<8>,
) {
    y = mux(select, low, high)
}
```

### One-hot decoder

`decode<N>(address)` produces an `N`-bit bus with exactly one selected bit. The selected bit is the unsigned value of
`address`. All other bits are 0. `address` must be `bits<clog2(N)>`, and `N` may range from 2 through 64.

```dust
let selected = decode<8>(address)
```

The decoder shares the inverted address bits between its outputs. Use it instead of comparing the same address against
every possible constant when building row selectors, register files, or displays.

### Latch

`latch(data, hold)` creates one bit of state:

```dust
stored = latch(data, hold)
```

Both arguments are one bit. When `hold` is 0, the latch follows `data`. When `hold` is 1, it keeps its previous value.

Unlike `let mut`, this creates actual stateful hardware.

### Clocked storage

A DFF (data flip-flop) is a one-bit memory element. Its data input is traditionally called `d`, and its stored output is
called `q`. In Dust, `dff(data, clock)` copies `data` into that stored bit when `clock` changes from 0 to 1:

```dust
q = dff(d, clock)
```

Changing `data` while `clock` remains 0 or 1 does not change `q`. Changing `clock` from 1 back to 0 does not store
anything. All DFFs connected to one clock store their inputs simultaneously when it changes from 0 to 1. The physical
DFF uses a subtraction comparator and a two-tick repeater to detect that change and briefly open a locked storage
repeater.

Register helpers apply DFF storage to a complete bus. Their width is inferred from `data`; an optional explicit width is
accepted as a check:

```dust
position = register(next_position, clock)
held = enabled_register<8>(next_value, enable, clock)
state = resettable_register(next_state, reset, clock)
```

`enabled_register` retains its old value when `enable` is 0. `resettable_register` has a synchronous reset: it stores
zero when `clock` changes from 0 to 1 while `reset` is 1. Enable and reset logic is composed from multiplexers in front
of ordinary DFFs.

The complete [`register.dust`](../examples/register.dust) example stores and exposes an 8-bit value. To write a value,
put it on `write_data`, set `write` to 1, and change `clock` from 0 to 1. Then set `write` back to 0. `read_data`
continuously exposes the stored value, so reading needs no separate action. Changes to `write_data`, and further clock
changes while `write` is 0, do not overwrite it. To write another value, return `clock` to 0, set up the new value with
`write` at 1, and change `clock` to 1 again.

### Generated clock

`clock<CLOCK_TICKS>(enabled)` creates a repeating physical clock signal. `CLOCK_TICKS` is the complete period: the
number of redstone ticks from one 0-to-1 clock change to the next. When `enabled` is 0, the clock stops and remains 0.

```dust
let system_clock = clock<10>(enabled)
stored = register(data, system_clock)
```

The subtraction-mode comparator loop can represent periods `6 + 4n`: 6, 10, 14, 18, and so on, up to 4094 ticks.
Other values are rejected during Dust compilation. The physical generator uses two matched repeater lanes. The two
repeaters in each row always have the same delay.

Physical compilation reports `minimumSafeStepTicks`/`minimumClockPeriodTicks`, hold slack, and maximum clock skew.
Supplying an explicit clock period through the compiler API makes setup, hold, or skew violations reject the build.
Externally stepped builds always reject hold violations and report their minimum safe interval.

### Persistent lamp display

`display<WIDTH, HEIGHT>` is a flush, persistent redstone output. Width and height may independently be any even value
from 8 through 64. Each pixel is a 2x2 lamp square.

Each pixel is stored by an ordinary transparent latch, and the latch outputs feed one contiguous flush lamp-matrix
hard macro.

Set the `clog2(WIDTH)`-bit `x` coordinate, the `clog2(HEIGHT)`-bit `y` coordinate, and `pixel_value`, then briefly
change `plot` from 0 to 1 and back to 0 to write that pixel. The value remains visible after the address and data inputs
change. Briefly pulse `plot_all` instead to write `pixel_value` to every pixel. This clears the display when the value
is 0 and fills it when the value is 1.
`plot` and `plot_all` are direct redstone controls and do not need a separate clock input.

```dust
module main(
    input x: bits<3>,
    input y: bits<3>,
    input pixel_value: bit,
    input plot: bit,
    input plot_all: bit,
    output screen: display<8, 8>,
) {
    screen = display_write(x, y, pixel_value, plot, plot_all)
}
```

The complete
[`display-demo.dust`](../examples/display-demo.dust) example exposes the five controls on a separate panel.

[`bresenham-display.dust`](../examples/bresenham-display.dust) is a complete clocked Bresenham line drawer.

The register-backed display remains available when independent pixel inputs or synchronous register semantics are more
useful:

`display<WIDTH, HEIGHT>(x, y, pixel_value, write, clear, clock)` creates a persistent framebuffer backed by ordinary
registers and presents it through a hard-macro lamp wall. Width and height may independently range from 8 through 64.
Each logical pixel is rendered as an adjacent 2x2 cluster of redstone lamps. Coordinates outside a non-power-of-two
display do not select a pixel.

On a 0-to-1 clock change, `write` stores `pixel_value` at `(x, y)`. `clear` has priority and synchronously clears every
pixel. Both controls therefore need a clock change before they take effect.

```dust
display<8, 8>(x, y, true, plot, clear, clock)
```

Larger displays are supported but contain proportionally more registers and routing.

## Calling modules

Modules can be composed by calling them like functions:

```dust
module half_adder(
    input a: bit,
    input b: bit,
    output sum: bit,
    output carry: bit,
) {
    sum = a ^ b
    carry = a & b
}

module full_adder(
    input a: bit,
    input b: bit,
    input cin: bit,
    output sum: bit,
    output cout: bit,
) {
    let first = half_adder(a, b)
    let second = half_adder(first.sum, cin)

    sum = second.sum
    cout = first.carry | second.carry
}
```

Arguments are positional and correspond to the called module's input ports in declaration order.

A module call returns an output bundle. Read individual outputs with `.name`, such as `first.sum` or `first.carry`.

Called modules are flattened into the caller. Dust does not preserve a separate physical module instance. Recursive
module calls are not allowed. Modules may appear in any order in the file.

Bundled library cells use the same specialization syntax and flatten directly into the ordinary Boolean netlist. A
single-output library cell returns its signal directly. A multi-output cell returns a named bundle. An outputless
library cell may be called as a statement, which is useful for stateful hardware sinks. Calls that produce an unused
value remain errors. A source module may not reuse the name of a bundled library cell.

Placement attributes attached to a module's top-level I/O apply when that module itself is compiled as the top level.
They are not propagated through a nested module call.

## Bundled arithmetic library

Dust bundles generic combinational arithmetic as parameterized Dust modules. They are parsed, specialized, and flattened
through the same path as source modules. `WIDTH` may span the normal
1-to-64-bit bus range, including the widened error buses used by coordinate algorithms.

| Module                                                       | Inputs                | Outputs                                          |
|--------------------------------------------------------------|-----------------------|--------------------------------------------------|
| `ripple_add<WIDTH>`                                          | `a`, `b`, `carry_in`  | `sum`, `carry_out`                               |
| `subtract<WIDTH>`                                            | `a`, `b`, `borrow_in` | `difference`, `borrow_out`                       |
| `negate<WIDTH>`                                              | `value`               | `result`                                         |
| `increment<WIDTH>`                                           | `value`               | `result`, `carry_out`                            |
| `decrement<WIDTH>`                                           | `value`               | `result`, `borrow_out`                           |
| `compare_unsigned<WIDTH>`                                    | `a`, `b`              | `equal`, `not_equal`, `less`, `greater`          |
| `compare_signed<WIDTH>`                                      | `a`, `b`              | `equal`, `not_equal`, `less`, `greater`          |
| `equal<WIDTH>` / `not_equal<WIDTH>`                          | `a`, `b`              | `y`                                              |
| `unsigned_less_than<WIDTH>` / `unsigned_greater_than<WIDTH>` | `a`, `b`              | `y`                                              |
| `signed_less_than<WIDTH>` / `signed_greater_than<WIDTH>`     | `a`, `b`              | `y`                                              |
| `absolute_difference<WIDTH>`                                 | `a`, `b`              | `difference`                                     |
| `alu<WIDTH = 4>`                                             | `a`, `b`, `operation` | `value`, `zero`, `negative`, `carry`, `overflow` |

Addition and subtraction are modulo `2^WIDTH`, with overflow reported separately as carry or borrow. Signed comparison
uses two's-complement interpretation.

Conventional short wrappers are also provided: `add`, `sub`, `neg`, `inc`, `dec`, `eq`, `neq`, `ult`, `ugt`, `slt`,
`sgt`, and `abs_diff`. For example:

```dust
let advanced = add<WIDTH>(position, velocity, false)
let ordered = slt<WIDTH>(advanced.sum, limit)
next = if ordered.y { advanced.sum } else { limit }
```

Bundled module names are reserved. Declaring a source module with one of these names is diagnosed as ambiguous.

### Small ALU

`alu` is a ready-to-use combinational ALU. Its width defaults to 4 bits.

```dust
let calculated = alu(a, b, operation)
value = calculated.value
zero = calculated.zero
```

Use `alu<8>(a, b, operation)` (or another width) when needed. The three-bit `operation` input selects:

| `operation` | Result                                   |
|-------------|------------------------------------------|
| `0b000`     | `a + b`                                  |
| `0b001`     | `a - b`                                  |
| `0b010`     | `a & b`                                  |
| `0b011`     | `a \| b`                                 |
| `0b100`     | `a ^ b`                                  |
| `0b101`     | `~a`                                     |
| `0b110`     | `1` when `a == b`, otherwise `0`         |
| `0b111`     | `1` when unsigned `a < b`, otherwise `0` |

`zero` and `negative` always describe `value`. `carry` is the addition carry. For subtraction it is one when no borrow
occurred. `overflow` reports signed two's-complement overflow for addition and subtraction. `carry` and `overflow` are
zero for logic and comparison operations.

## Physical placement attributes

Dust normally chooses placement automatically. Attributes let a design provide useful physical constraints or hints
without describing exact Minecraft coordinates.

Attributes do not change the logical behavior of the circuit.

### `#[panel]`

`#[panel]` turns a named top-level I/O group into a compact physical interface:

```dust
module adder4(
    #[panel]
    input operands {
        a: bits<4>,
        b: bits<4>,
        cin: bit,
    },
    #[panel]
    output result {
        sum: bits<4>,
        cout: bit,
    },
) {
    // ...
}
```

Without `#[panel]`, the ports are still grouped logically, but the physical compiler remains free to place them
independently.

A panel must be a named top-level I/O group. The compiler currently supports panels on the north or south edge. If no
`#[edge]` is given, the compiler chooses the panel edge automatically.

### `#[edge(...)]`

`#[edge]` constrains top-level I/O to a specific side of the physical design:

```dust
#[edge(north)] input controls { enable: bit },
#[edge(south)] output result { ready: bit },
```

The valid directions are:

```text
north
south
east
west
```

`#[edge]` may be attached to an individual top-level port or to a group.

A `#[panel]` can currently only use `north` or `south`.

### `#[near(...)]`

`#[near]` tells the placer that a value should preferably stay physically close to one or more other signals or groups:

```dust
#[near(operands)]
let p = a ^ b
```

Multiple targets can be given:

```dust
#[near(a, b)]
let p = a ^ b
```

For an internal `let`, targets can be existing local values, input ports, or named input groups. On a top-level port,
targets may also include outputs and output groups because placement is applied after the module has been elaborated:

```dust
module example(
    input controls { enable: bit },
    #[near(controls)] output ready: bit,
) {
    ready = ~enable
}
```

`#[near]` is an affinity hint. It influences placement cost but does not promise adjacency or a particular coordinate.

### `#[tier(...)]`

`#[tier]` forces a signal onto a physical routing/placement tier:

```dust
#[tier(1)]
let p = a ^ b
```

Tiers are numbered from 0. The argument must be a non-negative compile-time integer literal.

It can also constrain top-level I/O:

```dust
#[tier(0)] input a: bit,
```

Use explicit tiers only when the physical structure matters. Unannotated logic is normally better left to the placer.

### Combining attributes

Attributes can be stacked:

```dust
#[panel]
#[edge(north)]
input controls {
    enable: bit,
    reset: bit,
},
```

Internal placement attributes are attached to immutable `let` bindings:

```dust
#[tier(1)]
#[near(operands)]
let p = a ^ b
```

`#[edge]` and `#[panel]` are only valid for top-level I/O. Placement attributes cannot be attached to `let mut`
bindings.

## Blocks and scope

Braces create a nested scope:

```dust
{
    let temporary = a ^ b
    y = temporary
}
```

Bindings declared inside a block are not available outside it.

Loop bodies also get their own scope, with the loop index defined inside that scope.

Dust does not use semicolons. Statements are separated by their syntax and may be formatted across lines however is most
readable.

## Comments

Line comments start with `//`:

```dust
// one line
let p = a ^ b
```

Block comments use `/* ... */`:

```dust
/*
   multiple lines
*/
```

Block comments may be nested.

## Complete buildable example

```dust
module main(
    #[panel]
    input operands {
        a: bits<4>,
        b: bits<4>,
        cin: bit,
    },
    #[panel]
    output result {
        sum: bits<4>,
        cout: bit,
    },
) {
    let mut carry = cin

    for i in 0..4 {
        let p = a[i] ^ b[i]
        sum[i] = p ^ carry
        carry = (a[i] & b[i]) | (p & carry)
    }

    cout = carry
}
```

The loop is unrolled into four stages. Each Boolean operator becomes primitive logic, the output assignments become
module outputs, and the placement attributes tell the physical compiler to arrange both I/O groups as compact panels.

More examples are available in the [examples](../examples) directory.

## Current language boundaries

Dust is intentionally small. In particular, it currently has no runtime loops, recursive modules, or user-defined
types.

Certain features will likely be added in the future.
