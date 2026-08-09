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

See [language reference](docs/language.md) for the complete syntax.

## Editor support

[`editors/vscode`](editors/vscode) is a VS Code / VSCodium extension: `.dust` highlighting,
snippets, and a build command that reports `dustc` errors inline. It downloads a release binary for
you, so no toolchain is needed to use it. Tagged releases attach the packaged `dust-vscode.vsix`.

## LLM disclosure

A substantial amount of dustc was developed with the help of LLMs. The project is still directed, tested, and evaluated
by a human.