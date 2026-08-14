# dustc

`dustc` compiles the [Dust hardware language](docs/language.md) into placed and routed Minecraft redstone and writes the
result as a Sponge `.schem` file.

```text
Dust source -> Boolean netlist -> standard-cell placement -> redstone routing -> .schem
```

## Install dustc

### Prebuilt binary

The easiest way to use dustc is to download a binary from
the [GitHub releases](https://github.com/0x1bd/dustc/releases).

Prebuilt binaries are published for:

- Linux x86_64
- Linux aarch64
- macOS Apple Silicon
- Windows x86_64

On Linux or macOS, make the downloaded file executable and optionally put it on your `PATH`:

```shell
chmod +x dustc-linux-x86_64
mv dustc-linux-x86_64 ~/.local/bin/dustc
```

Then verify the installation:

```shell
dustc --version
```

You can also keep the executable in the repository or another working directory and invoke it as `./dustc`.

## Build from source with Gradle

The project targets Java 21. Clone the repository and use the included Gradle wrapper:

```shell
git clone https://github.com/0x1bd/dustc.git
cd dustc
./gradlew build
```

`build` compiles dustc and runs the test suite.

To run the JVM version directly through Gradle:

```shell
./gradlew run --args='build examples/adder4.dust -o adder4.schem'
```

To create an installable JVM distribution:

```shell
./gradlew installDist
```

The launcher will be written to:

```text
build/install/dustc/bin/dustc
```

### Native executable

The standalone release binary is built with GraalVM Native Image. Install a GraalVM JDK with `native-image`, then run:

```shell
./gradlew nativeCompile
```

The resulting executable is:

```text
build/native/nativeCompile/dustc
```

Or build it and copy it to the repository root in one step:

```shell
./gradlew installNative
./dustc --version
```

On Windows the executable has the usual `.exe` suffix.

## Quick start

Compile the included 4-bit adder:

```shell
dustc build examples/adder4.dust -o adder4.schem
```

A ready-to-use 4-bit ALU is included too:

```shell
dustc build examples/alu4.dust -o alu4.schem
```

By default, physical builds expose inputs as levers and outputs as lamps. For raw redstone terminals instead:

```shell
dustc build examples/adder4.dust -o adder4.schem --terminals
```

The top-level module is named `main`. This lets editor integrations such as **Dust: Build Schematic** build the active
file without asking which module to use:

```dust
module main(input a: bit, output y: bit) {
    y = ~a
}
```

A custom module can also be specified with `--module`:

```shell
dustc build design.dust -o design.schem --module cpu
```

Specialize top-level compile-time integer parameters by repeating `--param`:

```shell
dustc build display.dust --param WIDTH=13 --param HEIGHT=9 -o display.schem
```

For all CLI options:

```shell
dustc --help
```

## VS Code / VSCodium

The extension in [`editors/vscode`](editors/vscode) adds:

- Dust syntax highlighting
- snippets for the language and placement attributes
- `Dust: Build Schematic`
- inline compiler errors
- live physical-compilation progress
- automatic downloading of stable or nightly dustc binaries

Tagged releases include `dust-vscode.vsix`. Install it from the command line with either VS Code or VSCodium:

```shell
code --install-extension dust-vscode.vsix
```

```shell
codium --install-extension dust-vscode.vsix
```

You can also use **Extensions -> ... -> Install from VSIX...**.

After installing the extension, open the command palette and run:

```text
Dust: Download Latest dustc
```

The extension stores the downloaded compiler itself. If you already built dustc locally, no download is required: when
`dust.compilerPath` is empty, the extension searches in this order:

1. the compiler downloaded by the extension
2. `dustc` in the workspace root
3. `dustc` on `PATH`

To use a specific executable, set `dust.compilerPath` in VS Code settings.

For example:

```json
{
  "dust.compilerPath": "/home/me/src/dustc/dustc",
  "dust.buildArguments": [
    "--terminals"
  ]
}
```

To build the VS Code extension itself:

```shell
cd editors/vscode
npx @vscode/vsce package
```

This produces a `.vsix` that can be installed with the commands above.

## Language

A small Dust module looks like this:

```dust
module main(
    input controls { a: bit },
    output result { y: bit },
) {
    y = ~a
}
```

Dust supports buses, Boolean operators, compile-time loops and parameters, local bindings, module composition, a bundled
generic arithmetic/comparison library, multiplexers, latches, clocked registers, and physical placement attributes
such as `#[near(...)]`,
`#[tier(...)]`, `#[edge(...)]`, and `#[panel]`.

See the [language reference](docs/language.md) for the complete syntax and semantics.
The physical-cell format is documented in [text cell definitions](docs/cell-definitions.md).

Contributor conventions are documented in [`CONTRIBUTING.md`](CONTRIBUTING.md).

## LLM disclosure

LLMs are used in the development of dustc, alongside code written by humans. I still make the design decisions
and review and test LLM-generated changes.
