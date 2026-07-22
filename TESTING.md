# forge_netstress — test procedure

A small, scenario-driven Forge network stress harness. It carries no fix —
it only generates traffic (server) per whatever scenario is active, and
samples the JVM's direct NIO buffer pool (client) so the effect is visible
in the client log without any external tooling.

**This is a TEST TOOL.** It generates artificial, deliberately heavy
network traffic. Do not install it on, or point it at, any real/shared
world or the family server. Everything below assumes a **throwaway local
dedicated server**.

The one scenario shipped in v1, `baseline_4k_20hz` (4096-byte payload,
one packet per player per server tick, ~20/s/player, ~80 KB/s/player,
SimpleChannel S2C, consume-normally handler), is the same reproducer this
harness grew from: Forge 1.20.1's (47.4.18) `SimpleChannel` never releases
the pooled direct `ByteBuf` copy it allocates for every custom-payload
packet read off the wire. It is **active by default** — a fresh install
with no commands run behaves exactly like the standalone reproducer did.

## 1. Set up a clean throwaway server + client

- Download plain **Forge 1.20.1-47.4.18** (installer from
  files.minecraftforge.net) and run the server installer into a fresh empty
  directory, e.g. `~/mctest/server/`.
- Accept the EULA (`eula.txt` -> `eula=true`), start the server once to
  generate `mods/`, stop it.
- Drop **only** `dist/forge_netstress-0.1.1.jar` into `~/mctest/server/mods/`.
  No other mods.
- On the client side, install the matching Forge 1.20.1-47.4.18 profile in
  the vanilla launcher (or a CurseForge/Prism instance pinned to that
  version) and drop **only** `forge_netstress-0.1.1.jar` into that instance's
  `mods/`. No other mods.
- Start the server, then join it from the client over LAN/localhost. Make
  sure whoever is running the test is an op on the server (needed for the
  `/netstress` command — permission level 2).

## 2. Command flow: `/netstress`

Commands are unrestricted (no op/permission check) -- this is a test tool
for throwaway servers only, never a shared or family server.

- `/netstress list` — prints every known scenario (name + description) and
  marks which one is currently active (or that none is, if stopped).
- `/netstress run <name>` — switches the active scenario live, no restart.
  Tab-completes scenario names.
- `/netstress stop` — halts traffic generation. Note this only stops *new*
  traffic; any buffer pool growth already accumulated does not drain —
  that is expected, and is itself part of what the tool demonstrates (see
  step 3).

Nothing above is required to reproduce the baseline behavior — the mod
starts with `baseline_4k_20hz` already active. The commands exist for
switching to other scenarios later and for stopping traffic mid-session
without restarting the server.

## 3. Baseline: watch the pool climb

- With `baseline_4k_20hz` active (the default — no command needed), idle
  in the world for 10-15 minutes (walking around is fine; nothing special
  needs to happen).
- Watch the client log (`logs/latest.log` or the in-game debug/log window)
  for lines like:

  ```
  [forge_netstress] direct pool: used=12.34 MB capacity=12.34 MB buffers=45
  ```

  One line every ~10 seconds (200 client ticks). On stock Forge this number
  should **climb monotonically** for as long as you stay connected — it
  should never drop back down, even briefly, while connected. Over 10-15
  minutes at ~80 KB/s of leaked payload you should see several MB/min of
  growth (the exact rate depends on packet/pool bookkeeping overhead, but
  the trend, not the exact slope, is the thing to confirm).
- Run `/netstress stop`. Traffic stops (no more `PLAYER` sends each tick),
  but the already-logged pool number does **not** drop — it holds at
  whatever it last climbed to. Stopping traffic halts the climb; it does
  not drain the pool. That is the bug being demonstrated: Forge's
  `SimpleChannel` receive path never releases the buffers it already
  allocated, so nothing short of a disconnect (or a fix) reclaims them.
- This confirms the reproducer: stock Forge is leaking one pooled direct
  buffer per received custom-payload packet.

## 4. Optional contrast A — singleplayer shows no climb

- Quit the multiplayer server, start **singleplayer** on the same client
  (same `forge_netstress-0.1.jar` still installed — its server-side logic
  also runs in an integrated server, defaulting to `baseline_4k_20hz`
  again, so it will still be sending itself packets and the client will
  still log samples).
- Idle 10-15 minutes and watch the same log lines.
- Expected: the number should **not** show the same sustained upward climb
  — buffers allocated on the integrated-server loopback path are ordinary
  heap-backed/GC-collectable buffers in practice for a singleplayer world,
  so the JVM reclaims them under normal GC pressure instead of accumulating
  in the pooled-direct arena the way a real remote connection's receive
  path does. Treat any residual noise/small fluctuation as GC breathing,
  not a leak — the qualitative difference from step 3's relentless climb is
  the point, not a specific number.

## 5. Optional contrast B — install the fix and repeat

- Return to the dedicated multiplayer server from step 1.
- Add `forge_netleak_fix-0.1.1.jar` (built from
  the [forge-netleak-fix](https://github.com/ecpunk/forge-netleak-fix) release) to the **client's** `mods/` folder alongside
  `forge_netstress-0.1.jar` (the fix only needs to be on the receiving
  side to prove the point, but it's side-agnostic and safe to also add
  server-side).
- Rejoin (traffic is active by default), idle 10-15 minutes, watch the same
  log lines.
- Expected: the direct-pool number now **flatlines or breathes** (rises
  with active traffic, falls back down as the mixin releases buffers)
  instead of climbing without bound — the same traffic pattern as step 3,
  but the leak is plugged. This is the fault-injection pass on the gauge
  itself: it proves the pool numbers actually distinguish a leaking build
  from a fixed one, not just "network traffic happened."

## 6. Optional deeper verification — Netty leak detection

Add this JVM flag to the **client's** launch args (in the launcher's JVM
arguments field, or `user_jvm_args.txt` for a dedicated-server-style
launch) for a run of step 3 or step 5:

```
-Dio.netty.leakDetection.level=advanced
```

With this enabled, Netty itself will periodically log `LEAK:` records to
the client log whenever a pooled buffer is garbage-collected without ever
having been released, including a **creation stack trace** — on stock Forge
(step 3) this should surface Forge's own allocation site inside the
`ClientboundCustomPayloadPacket` / `SimpleChannel` receive path, which is
the direct evidence tying the climbing pool numbers to the specific
never-released buffer under test. With the fix installed (step 5), these
`LEAK:` records should stop appearing. This flag adds real overhead
(sampling + stack capture) — use it only for this verification pass, not
for normal play.
