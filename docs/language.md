# Dust language

## Modules

A module is one circuit:

```dust
module inverter(
    input controls { a: bit },
    output result { y: bit },
) {
    y = ~a
}
```

| Code | Meaning |
|---|---|
| `module name(...) { ... }` | Make a circuit |
| `input a: bit` | Add one input wire |
| `output y: bit` | Add one output wire |
| `input a: bits<8>` | Add an 8-bit input |
| `input controls { ... }` | Put inputs in a named group |
| `output result { ... }` | Put outputs in a named group |

A bus can have 1 to 64 bits. Names use lower-case letters, numbers, and `_`. A name must start with a letter.

## Logic

| Code | Gate | Order |
|---|---|---:|
| `~a`, `!a` | NOT | 1 |
| `a & b`, `a and b` | AND | 2 |
| `a ^ b` | XOR | 3 |
| `a | b`, `a or b` | OR | 4 |
| `bus[i]` | Get bit `i` | — |

Smaller order numbers run first. Gates also work on buses of the same size. Use parentheses to choose the order yourself.

## Names and outputs

| Code | Meaning |
|---|---|
| `let p = a ^ b` | Give a value a name |
| `let mut carry = cin` | Make a name that can change |
| `carry = next` | Change that name |
| `sum = value` | Set a whole output |
| `sum[i] = value` | Set one output bit |

Set every output bit once. `let mut` is useful for carry chains.

## Loops

```dust
for i in 0..8 {
    y[i] = a[i] ^ b[i]
}
```

| Range | Values of `i` |
|---|---|
| `0..8` | `0` to `7` |
| `0..=7` | `0` to `7` |

The loop makes another copy of the gates for each value of `i`. Loop values must be known when the file is built.

## Built-in parts

| Code | Meaning |
|---|---|
| `mux(select, low, high)` | Pick `low` for 0 and `high` for 1 |
| `latch(data, hold)` | Store one bit; high `hold` keeps the old value |

`low` and `high` must have the same size. `select`, `data`, and `hold` are one bit each.

## Using another module

Pass inputs in the same order as they appear in the module:

```dust
let stage = half_adder(a, b)
sum = stage.sum
carry = stage.carry
```

Use `.name` to read an output from the called module. Modules can appear in any order in the file.

## Minecraft layout

| Part | Layout |
|---|---|
| Inputs | Levers with name signs on the lower layer |
| Outputs | Lamps with name signs on the upper layer |
| Input wires | Lower wire layer |
| Output wires | Upper wire layer |
| Wire spacing | One route every three blocks |

`//` starts a line comment. Put a block comment between `/*` and `*/`.
