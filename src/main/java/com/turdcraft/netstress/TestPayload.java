package com.turdcraft.netstress;

import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

/**
 * The one message type sent over the {@code forge_netstress:test} channel.
 * Its entire purpose is to move a block of bytes across the wire so Forge
 * allocates and hands off a pooled direct {@code ByteBuf} for every
 * packet, exactly like any other custom-payload mod packet (KubeJS
 * network events, mod-menu-sync packets, etc.).
 *
 * Size comes from the active {@link Scenario} rather than being a
 * hardcoded constant, so the wire format is self-describing: encode
 * writes a length prefix followed by the bytes, and decode reads however
 * many bytes that prefix says are there -- no fixed SIZE needs to be
 * shared between sender and reader.
 *
 * <p>{@code encode}/{@code decode} take {@link FriendlyByteBuf} (SimpleChannel's
 * required signature), but immediately upcast the reference to plain {@link
 * ByteBuf} and only ever call methods declared there ({@code writeInt}/
 * {@code writeBytes}/{@code readInt}/{@code readBytes}). {@code
 * FriendlyByteBuf} itself declares convenience wrappers over these
 * ({@code writeByteArray}/{@code readByteArray}) that v0.1 used directly —
 * those are vanilla-declared members, so the Methodref the compiler would
 * emit for them is owned by {@code net/minecraft/network/FriendlyByteBuf},
 * which crashes with {@code NoSuchMethodError} against the Forge runtime's
 * SRG-named jars (the same class of bug as the {@code Commands.literal}
 * crash this release fixes). {@code FriendlyByteBuf extends
 * io.netty.buffer.ByteBuf} directly, so the upcast is a plain widening
 * reference conversion -- no cast bytecode, and the emitted Methodref owner
 * for every call below is {@code io/netty/buffer/ByteBuf}, which is fine.
 *
 * The decoder reads the bytes and immediately discards them — there is
 * nothing else in this class to leak; the leak under test is entirely on
 * Forge's side, in the buffer that carried this payload off the wire
 * (see {@code NetworkEvent.payload} / {@code SimpleChannel#networkEventListener}).
 */
public final class TestPayload {

    private static final Random RNG = new Random();

    /** Template bytes reused for every outgoing packet of a given size,
     *  one per size actually used by an active scenario. The leak under
     *  test is in Forge's per-packet receive-side buffer, not in this
     *  payload's content, so there is no need to re-randomize per send —
     *  it only needs to be a real block of bytes on the wire, and reusing
     *  a cached template means this sender allocates no new byte[] per
     *  packet (the sender must not leak by construction). */
    private static final Map<Integer, byte[]> TEMPLATES = new ConcurrentHashMap<>();

    private final byte[] data;

    public TestPayload(int size) {
        this.data = templateFor(size);
    }

    private TestPayload(byte[] data) {
        this.data = data;
    }

    private static byte[] templateFor(int size) {
        return TEMPLATES.computeIfAbsent(size, TestPayload::newTemplate);
    }

    private static byte[] newTemplate(int size) {
        byte[] template = new byte[size];
        RNG.nextBytes(template);
        return template;
    }

    static void encode(TestPayload msg, FriendlyByteBuf buf) {
        ByteBuf raw = buf;
        raw.writeInt(msg.data.length);
        raw.writeBytes(msg.data);
    }

    static TestPayload decode(FriendlyByteBuf buf) {
        ByteBuf raw = buf;
        int length = raw.readInt();
        byte[] received = new byte[length];
        raw.readBytes(received);
        // Decoded and discarded on purpose — this class carries no state
        // worth keeping. The point is that Forge has already allocated
        // (and, per the bug under test, never releases) the pooled direct
        // ByteBuf this FriendlyByteBuf wraps.
        return new TestPayload(received);
    }

    static void handle(TestPayload msg, Supplier<NetworkEvent.Context> ctx) {
        // No-op: the payload was already fully consumed in decode(). Just
        // acknowledge the packet so SimpleChannel considers it handled.
        ctx.get().setPacketHandled(true);
    }
}
