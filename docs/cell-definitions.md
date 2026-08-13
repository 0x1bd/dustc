# Text cell definitions

Physical cells in `src/main/resources/org/kvxd/dust/technology/cells` use a simple text format.

## Parameters and expressions

Parameters are declared on the cell header. Ranges are inclusive. A trailing default makes the parameter optional.

```text
cell tile<WIDTH: int in 1..32 = 8, HEIGHT: int in 1..32 = 8>
```

Integer expressions support parentheses, checked `+`, `-`, `*`, `/`, and `%`, together with `abs`, `min`, `max`, and
`clog2`. `for i in A..B` uses an exclusive upper bound and `for i in A..=B` uses an inclusive upper bound. Loops may
nest in `pins`, `layout`, `block-entities`, `observations`, and `timing` sections.

## Geometry and metadata

Palette values may be technology template names or complete Minecraft block states:

```text
palette:
# = support
R = minecraft:repeater[delay=4,facing=north,locked=false,powered=false]
C = minecraft:comparator[facing=north,mode=subtract,powered=false]
B = minecraft:barrel[facing=north,open=false]
```

Generated pins, blocks, wires, included cells, block entities, and observations use compile-time expressions:

```text
pins:
for x in 0..WIDTH {
    data[x] input @(x * 2),1,(HEIGHT + 1)
}

layout:
for y in 0..HEIGHT {
    for x in 0..WIDTH {
        block # @x,0,y
    }
}
include decoder<WIDTH> @0,1,0
wire dust @0,1,2

block-entities:
barrel @2,1,0 signal=9
sign @3,1,0 text="line one|line two" color=black glowing=false

observations:
for y in 0..HEIGHT {
    for x in 0..WIDTH {
        pixel[x,y] @x,0,y
    }
}
clock @0,1,0
```

Barrels accept either `signal=0..15` or `slots=` plus an `items=` list in `SLOT@ITEM*COUNT;...` form. Generated block
entities are copied when their cell is placed and are preserved by schematic writing and reading.

## Timing and hard-macro placement

Physical timing can override the logical cell timing. Delay ranges are inclusive.

```text
timing:
arc data -> result rise=1..3 fall=1..3
setup-hold data clock edge=rise setup=1 hold=1

placement:
exclusive-row=true
visible-edge=north
```

Adding a `placement` section makes the definition a hard macro. `visible-edge` accepts `north`, `south`, `east`, `west`,
or `none`.
