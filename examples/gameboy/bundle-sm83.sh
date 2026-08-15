#!/usr/bin/env bash
set -euo pipefail
gameboy_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
output=${1:?}
awk 'FNR == 1 && NR != 1 { print "" } { print }' \
    "$gameboy_dir/sm83-register-file.dust" \
    "$gameboy_dir/sm83-alu.dust" \
    "$gameboy_dir/sm83-misc.dust" \
    "$gameboy_dir/sm83-cb.dust" \
    "$gameboy_dir/sm83-address-state.dust" \
    "$gameboy_dir/sm83-fetch-control.dust" \
    "$gameboy_dir/sm83-ram.dust" \
    "$gameboy_dir/sm83-core.dust" > "$output"
