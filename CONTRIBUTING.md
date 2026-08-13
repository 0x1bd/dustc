# Contributing to dustc

Thanks for contributing.

## Kotlin source layout

Keep Kotlin sources organized consistently:

* One top-level class, interface, enum, or object per file.
* Name the file after that type.
* Keep packages narrow and match the directory layout to the package name.
* Use nested types only when they clearly belong to the enclosing type.
* Top-level functions and constants can share a file when they belong together.

Try to follow the existing structure instead of introducing new general-purpose `util` packages or large catch-all files.

## Changes

Keep changes reasonably focused. If you change behavior, add or update tests where appropriate.

For compiler and physical-layout changes, generated output still needs to be valid. Don't work around failing validation just to make a test case pass.

If you change user-facing syntax or behavior, update the relevant documentation or examples as well.

## Before submitting

Run:

```sh
./gradlew test
```

and make sure it passes.

For larger changes, briefly describe what changed, why, and anything reviewers should know about the implementation.
