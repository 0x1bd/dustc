# Dust for VS Code / VSCodium

Editor support for the [Dust](https://github.com/0x1bd/dustc) language

## Getting the compiler

Run **Dust: Download Latest dustc** from the command palette. The binary lands in the extension's
global storage and is used automaticall.

Prebuilt binaries exist for Linux (x86\_64, aarch64), macOS (Apple Silicon), and Windows (x86\_64).
On any other platform, build the compiler yourself and point `dust.compilerPath` at it:

```shell
./gradlew nativeCompile
```

When `dust.compilerPath` is empty the extension resolves, in order: the downloaded binary, a
`dustc` executable in a workspace folder root, then `dustc` on `PATH`.

## Building

Name the schematic's top-level module `main`, then click **Build Schematic** in the editor title bar or run
**Dust: Build Schematic** from the command palette. The extension writes the `.schem` next to the active `.dust` file.
Legacy sources can select a differently named top-level module with `--module` in `dust.buildArguments`.

## Settings

| Setting                   | Default       | Meaning                                           |
|---------------------------|---------------|---------------------------------------------------|
| `dust.compilerPath`       | `""`          | Explicit `dustc` path, overriding the search      |
| `dust.compilerRepository` | `0x1bd/dustc` | GitHub repository to download releases from       |
| `dust.updateChannel`      | `stable`      | `stable` for the newest tag, `nightly` for `main` |
| `dust.buildArguments`     | `[]`          | Extra `dustc build` arguments, e.g. `--terminals` |

`stable` installs the newest tagged release. `nightly` follows the rolling prerelease rebuilt on
every push to `main`.

## Installing from a `.vsix`

VSCodium cannot reach the Visual Studio Marketplace, so install the packaged extension directly.
Each tagged release attaches `dust-vscode.vsix`:

```shell
codium --install-extension dust-vscode.vsix
```

To build it from this repository:

```shell
cd editors/vscode
npx @vscode/vsce package
```
