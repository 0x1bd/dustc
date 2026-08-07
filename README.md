# dustc

`dustc` compiles a small hardware language into placed and routed Minecraft redstone. The output is a Sponge `.schem`
file.

## Quick start

```shell
./dustc build examples/adder4.dust -o adder4.schem
```

| Command                                                  | Result                                       |
|----------------------------------------------------------|----------------------------------------------|
| `./dustc build design.dust -o design.schem`              | Schematic with lever inputs and lamp outputs |
| `./dustc build design.dust -o design.schem --terminals`  | Schematic with redstone terminals            |
| `./dustc build design.dust -o design.schem --module cpu` | Selects `cpu` from a multi-module file       |
| `./dustc --help`                                         | CLI reference                                |

## Language

| Construct                | Meaning                                           |
|--------------------------|---------------------------------------------------|
| `bit`                    | One signal                                        |
| `bits<8>`                | Eight-bit bus, least-significant bit at index 0   |
| `&`, `                   | `, `^`, `~`                                       | AND, OR, XOR, NOT |
| `let x = expression`     | Named signal expression                           |
| `let mut x = expression` | Rebindable elaboration value                      |
| `for i in 0..8 { ... }`  | Eight copies of the enclosed hardware             |
| `mux(select, low, high)` | Equal-width multiplexer                           |
| `latch(data, hold)`      | One-bit active-low-hold latch                     |
| `module_name(a, b)`      | Inline module instance returning an output bundle |

See [language reference](docs/language.md) for the complete syntax.
