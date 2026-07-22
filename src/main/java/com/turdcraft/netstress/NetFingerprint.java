package com.turdcraft.netstress;

import com.mojang.logging.LogUtils;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.util.ResourceLeakDetector;
import io.netty.util.internal.PlatformDependent;
import org.slf4j.Logger;

/**
 * One-shot netty memory-mode fingerprint, logged at mod construction on
 * both sides, plus arming netty's own leak detector at PARANOID (every
 * buffer tracked, full allocation stack on every leak report).
 *
 * Why this exists: the same missing release in Forge's custom-payload read
 * path produces two very different symptoms depending on which allocator
 * the packet buffer's lineage runs through. Copies descended from the
 * POOLED channel allocator pin 4 MiB arena chunks forever (unreclaimable
 * ratchet); copies descended from an UNPOOLED allocator are cleaner-backed
 * and a GC can rescue them (sawtooth). This line turns "which mode is this
 * instance in" from an inference into a gauge reading.
 *
 * PARANOID leak detection is deliberate and unconditional: this is a
 * diagnostic harness for throwaway rigs, and a leaked-buffer report with a
 * stack trace is exactly the artifact it exists to produce.
 */
final class NetFingerprint {

    private static final Logger LOGGER = LogUtils.getLogger();

    private NetFingerprint() {
    }

    static void logAndArm() {
        ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.PARANOID);
        PooledByteBufAllocator pooled = PooledByteBufAllocator.DEFAULT;
        LOGGER.info("[{}] allocator fingerprint: default={} pooledChunkSize={} directArenas={} "
                        + "directBufferPreferred={} hasUnsafe={} usedDirectMemory={} "
                        + "props[allocator.type={} maxOrder={} noUnsafe={}] leakDetector={}",
                NetStress.MODID,
                ByteBufAllocator.DEFAULT.getClass().getSimpleName(),
                pooled.metric().chunkSize(),
                pooled.metric().numDirectArenas(),
                PlatformDependent.directBufferPreferred(),
                PlatformDependent.hasUnsafe(),
                PlatformDependent.usedDirectMemory(),
                System.getProperty("io.netty.allocator.type"),
                System.getProperty("io.netty.allocator.maxOrder"),
                System.getProperty("io.netty.noUnsafe"),
                ResourceLeakDetector.getLevel());
    }
}
