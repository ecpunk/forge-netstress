package com.turdcraft.netstress;

/**
 * A named, declarative network-stress test case: how big a payload, how
 * often to send it, and what it is for. Adding a new stress test means
 * adding an entry to {@link Scenarios} — nothing in the tick handler or
 * network layer needs to change.
 *
 * Deliberately minimal for v1: channel type (SimpleChannel today,
 * EventNetworkChannel later), direction (S2C today, C2S later), and
 * handler behavior (consume-normally today, throw/retain/release later)
 * are all future axes. Do not add fields for them speculatively — the
 * registry pattern below is what matters, not a fully general schema
 * nobody has needed yet.
 */
public final class Scenario {

    private final String name;
    private final int payloadBytes;
    private final int packetsPerTick;
    private final String description;

    public Scenario(String name, int payloadBytes, int packetsPerTick, String description) {
        this.name = name;
        this.payloadBytes = payloadBytes;
        this.packetsPerTick = packetsPerTick;
        this.description = description;
    }

    public String name() {
        return name;
    }

    public int payloadBytes() {
        return payloadBytes;
    }

    /** Packets sent per player per server tick. 1 = every tick (~20/s). */
    public int packetsPerTick() {
        return packetsPerTick;
    }

    public String description() {
        return description;
    }
}
