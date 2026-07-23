# Forge NetStress

A scenario-based network stress harness for MinecraftForge 1.20.1. It generates
controlled custom-payload traffic between a server and its clients and logs the
client's direct memory pool, so networking behavior that only shows up under
sustained packet load becomes visible in a plain log file.

Built while diagnosing a direct-memory leak in Forge's SimpleChannel receive
path (see [MinecraftForge#10861](https://github.com/MinecraftForge/MinecraftForge/issues/10861)).
Scenario one is the minimal reproducer for that issue.

## Status

v0.1.2, live-verified. Two scenarios, one for each observed symptom of the
#10861 missing release (see below), both confirmed against a stock Forge
47.4.18 dedicated server with no other mods installed. The intent is to grow
this into a small library of scenarios (payload sizes and rates, both channel
types, both directions, different handler behaviors) that can act as a partial
conformance and stress suite for Forge's networking.

## How it works

Install the same jar on a throwaway dedicated server and a client. On the
server it sends each connected player test payloads via a normal SimpleChannel
registration. On the client it logs a line every ten seconds with the JVM's
direct buffer pool statistics, plus a one-time allocator fingerprint at
startup, and arms Netty's leak detector at PARANOID so leaked buffers get
stack-traced `LEAK:` reports in the client log:

```
[forge_netstress] direct pool: used=142 MB capacity=142 MB buffers=310
```

The missing release in Forge's receive path has two distinct symptoms, forked
by the server's `network-compression-threshold` (256 by default), and the two
shipped scenarios reproduce one each:

- `baseline_4k_20hz` (4096 bytes, 20 packets/s, active by default): payloads
  over the threshold are decompressed into heap-backed buffers, so the leaked
  copies can still be garbage-collected. The pool climbs, then drops when GC
  runs — a sawtooth. The stack-traced `LEAK:` records naming
  `SimpleChannel.networkEventListener` show up most readily on this path,
  where collection of the leaked buffer also reclaims its memory. (Records
  can appear in the pooled case too — the wrapper object gets collected while
  the arena memory stays lost — and Netty deduplicates reports, so the record
  count never tracks leak volume. See TESTING.md.)
- `small_128b_200hz` (128 bytes, 200 packets/s): payloads under the threshold
  stay in Netty's pooled allocator, and the leaked copies pin 4 MB arena
  chunks no GC can ever reclaim. Pool capacity steps up 4 MB at a time and
  never comes back down until disconnect — the unreclaimable variant that
  exhausts direct memory on long sessions in real modpacks.

See TESTING.md for the full procedure and expected log signatures, including
two contrast runs (singleplayer, and with the
[forge-netleak-fix](https://github.com/ecpunk/forge-netleak-fix) mod installed).

## Commands

```
/netstress list          show scenarios and which is active
/netstress run <name>    start a scenario
/netstress stop          stop traffic
```

Commands are unrestricted (no permission check) — this is a test tool for
throwaway servers only, never a shared or family server.

Stopping traffic halts the climb. The pool not draining afterward is the
behavior under investigation, not a bug in this tool.

## Warning

This is a test tool. It deliberately generates artificial network traffic.
Do not install it on a server people actually play on.

## Build

No Gradle. Compiles with plain javac against the jars of an existing Forge
1.20.1 installation:

```
FORGE_LIBS=/path/to/libraries ./build.sh
```

See the script header for details. A prebuilt jar is in `dist/`.

## License

MIT
