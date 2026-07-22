# Forge NetStress

A scenario-based network stress harness for MinecraftForge 1.20.1. It generates
controlled custom-payload traffic between a server and its clients and logs the
client's direct memory pool, so networking behavior that only shows up under
sustained packet load becomes visible in a plain log file.

Built while diagnosing a direct-memory leak in Forge's SimpleChannel receive
path (see [MinecraftForge#10861](https://github.com/MinecraftForge/MinecraftForge/issues/10861)).
Scenario one is the minimal reproducer for that issue.

## Status

v0.1, early. One scenario so far. The mod builds and passes static verification;
live test results will be recorded here as they happen. The intent is to grow
this into a small library of scenarios (payload sizes and rates, both channel
types, both directions, different handler behaviors) that can act as a partial
conformance and stress suite for Forge's networking.

## How it works

Install the same jar on a throwaway dedicated server and a client. On the
server it sends each connected player a test payload every tick (scenario
`baseline_4k_20hz`: 4096 bytes, 20 packets per second, via a normal
SimpleChannel registration, active by default). On the client it logs a line
every ten seconds with the JVM's direct buffer pool statistics:

```
[forge_netstress] direct pool: used=142 MB capacity=142 MB buffers=310
```

On stock Forge 1.20.1 that number climbs for as long as traffic flows and never
comes back down. See TESTING.md for the full procedure, including two contrast
runs (singleplayer, and with the
[forge-netleak-fix](https://github.com/ecpunk/forge-netleak-fix) mod installed).

## Commands

Ops (permission level 2) on the server:

```
/netstress list          show scenarios and which is active
/netstress run <name>    start a scenario
/netstress stop          stop traffic
```

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
