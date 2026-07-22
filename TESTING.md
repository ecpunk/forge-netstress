# forge_netstress — test procedure

A small, scenario-driven Forge network stress harness. It carries no fix —
it only generates traffic (server) per whatever scenario is active, and
samples the JVM's direct NIO buffer pool (client) so the effect is visible
in the client log without any external tooling. As of 0.1.2 it also arms
Netty's own leak detector at PARANOID automatically and logs a one-line
allocator fingerprint at startup (see step 3).

**This is a TEST TOOL.** It generates artificial, deliberately heavy
network traffic. Do not install it on, or point it at, any real/shared
world. Everything below assumes a **throwaway local dedicated server**.

## The two scenarios, and why payload size matters

Forge 1.20.1 (47.4.18) `SimpleChannel` never releases the direct `ByteBuf`
copy it allocates for every custom-payload packet read off the wire. What
that missing release *does* to the process depends on which allocator the
packet's buffer came from, and vanilla's network pipeline forks that by
packet size at the server's `network-compression-threshold` (256 by
default):

- A packet **at or over** the threshold arrives compressed and is inflated
  into a plain heap `byte[]`, so the leaked copy descends from an
  **unpooled** allocator. It is cleaner-backed: a GC pass can still reclaim
  it. The pool gauge shows a **sawtooth** — climb, then a drop when GC
  runs. Leak present, symptom mild.
- A packet **under** the threshold passes through as a slice of the
  **pooled** wire buffer, and Netty's `readBytes`/`copy` allocate from the
  source buffer's own allocator. The leaked copy is a live reference into a
  pooled 4 MiB arena chunk, the arena holds the chunk, and no GC can ever
  reclaim it. The gauge shows a **ratchet** — capacity climbs in 4 MB steps
  and never returns anything until disconnect.

Both scenarios exercise the same missing release; they differ only in
which side of that fork they land on:

- `baseline_4k_20hz` — 4096-byte payload, one packet per player per server
  tick (~20/s/player). Over the threshold → the **sawtooth** variant. This
  is the scenario to pair with the leak-detector output (step 5), because
  Netty only prints a `LEAK:` record when a leaked buffer is
  garbage-collected, which only ever happens on this path. **Active by
  default** on a fresh install.
- `small_128b_200hz` — 128-byte payload, ten packets per player per server
  tick (~200/s/player). Under the threshold → the **ratchet** variant, the
  one that exhausts `-XX:MaxDirectMemorySize` on long sessions in real
  modpacks (chatty mod sync traffic is mostly small packets). Switch to it
  live with `/netstress run small_128b_200hz`.

## 1. Set up a clean throwaway server + client

- Download plain **Forge 1.20.1-47.4.18** (installer from
  files.minecraftforge.net) and run the server installer into a fresh empty
  directory, e.g. `~/mctest/server/`.
- Accept the EULA (`eula.txt` -> `eula=true`), start the server once to
  generate `mods/`, stop it. Leave `network-compression-threshold` at its
  default of 256 — the size fork above depends on it.
- Drop **only** `dist/forge_netstress-0.1.2.jar` into `~/mctest/server/mods/`.
  No other mods.
- On the client side, install the matching Forge 1.20.1-47.4.18 profile in
  the vanilla launcher (or a CurseForge/Prism instance pinned to that
  version) and drop **only** `forge_netstress-0.1.2.jar` into that
  instance's `mods/`. No other mods.
- Start the server, then join it from the client over LAN/localhost.

## 2. Command flow: `/netstress`

Commands are unrestricted (no op/permission check) — this is a test tool
for throwaway servers only.

- `/netstress list` — prints every known scenario (name + description) and
  marks which one is currently active (or that none is, if stopped).
- `/netstress run <name>` — switches the active scenario live, no restart.
  Tab-completes scenario names.
- `/netstress stop` — halts traffic generation. Stopping only stops *new*
  traffic; ratcheted pool capacity does not drain — that is expected, and
  is itself part of what the tool demonstrates.

## 3. Read the startup fingerprint

At mod construction, both sides log one line like:

```
[forge_netstress] allocator fingerprint: default=PooledByteBufAllocator pooledChunkSize=4194304 directArenas=44 directBufferPreferred=true hasUnsafe=true ... leakDetector=PARANOID
```

This records which allocator mode the JVM is actually in, so a run's
result is never left arguing with an assumption. On a stock setup expect
`PooledByteBufAllocator` with 4 MiB chunks — the pooled allocator is the
stock default; the sawtooth-vs-ratchet difference comes from the per-packet
compression fork above, not from any global mode.

## 4. Run the two variants

Watch the client log for lines like:

```
[forge_netstress] direct pool: used=12.34 MB capacity=12.34 MB buffers=45
```

One line every ~10 seconds (200 client ticks).

- **Sawtooth:** with `baseline_4k_20hz` active (default, no command
  needed), idle 10-15 minutes. Expect steady climb at a few MB/min with
  the buffer count tracking the packet rate, punctuated by large drops when
  GC runs. The leak is real (step 5 proves it) but the memory is
  rescuable.
- **Ratchet:** run `/netstress run small_128b_200hz` and idle 10-15
  minutes. Expect capacity to step up in 4 MB increments with a small,
  near-constant buffer count (each pool chunk registers as one buffer),
  and **no drops, ever**, for as long as you stay connected. `/netstress
  stop` freezes the number; it does not lower it. This is the
  direct-memory exhaustion variant as seen in real modpacks, reproduced
  with zero third-party mods.

## 5. Read the leak-detector output

No JVM flag needed — 0.1.2 arms `-Dio.netty.leakDetection.level=paranoid`
equivalent automatically. During or after a `baseline_4k_20hz` run, the
client log will contain `LEAK:` records with full creation and access stack
traces. Expect them to name Forge's receive path:

```
net.minecraftforge.network.simple.IndexedMessageCodec.tryDecode
net.minecraftforge.network.simple.SimpleChannel.networkEventListener
```

Forge's own login handshake (`HandshakeMessages$S2CRegistry`) shows up in
these records too — the handshake payloads leak through the same path
before any test traffic flows. Remember the asymmetry from step 4: `LEAK:`
records print at GC time, so only the sawtooth variant can produce them.
The ratchet variant's buffers are never collected, which is exactly the
problem; its evidence is the gauge, not the detector.

## 6. Optional contrast A — singleplayer shows no climb

Quit multiplayer and open a singleplayer world with the same jar
installed (the integrated server sends the same default traffic). Expect
no sustained climb: the integrated-server loopback path uses ordinary heap
buffers and never touches the compression fork, so leaked copies are
GC-collectable. The qualitative difference from step 4's ratchet is the
point.

## 7. Optional contrast B — install the fix and repeat

Add `forge_netleak_fix-0.1.1.jar` (from
[forge-netleak-fix](https://github.com/ecpunk/forge-netleak-fix)) to the
**client's** `mods/` alongside the harness, rejoin, and repeat both
variants of step 4. Expect the sawtooth to flatten into gentle breathing
and the ratchet not to ratchet — same traffic, leak plugged, and the
`LEAK:` records stop appearing. This is the fault-injection pass on the
gauge itself: it proves the pool numbers distinguish a leaking build from
a fixed one, not just "network traffic happened."
