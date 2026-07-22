package com.turdcraft.netstress;

import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;

import com.mojang.logging.LogUtils;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

/**
 * Client-side observer. Samples the JVM's "direct" {@link BufferPoolMXBean}
 * every 200 client ticks (~10s at 20 tps) and logs used/capacity/count,
 * tagged {@code [forge_netstress]}.
 *
 * The active scenario is server-side state ({@link ServerTickHandler#ACTIVE})
 * and is not synced to the client, so these log lines deliberately do not
 * name a scenario — building that sync is not worth it for v1 (nothing here
 * needs it to be useful: the pool numbers are meaningful on their own, and
 * whoever is running the throwaway test rig already knows which scenario
 * they started via {@code /netstress}).
 *
 * {@code @EventBusSubscriber(value = Dist.CLIENT, ...)} makes Forge's
 * annotation-driven subscriber scan skip registering this class entirely
 * when running as (or embedded in) a dedicated server, so this jar loads
 * cleanly on a dedicated server even though this class exists in it — the
 * class itself only references common JRE / Forge-common types (no
 * client-only Minecraft classes), so it is safe even to load, but is never
 * instantiated/registered off the client dist.
 */
@Mod.EventBusSubscriber(modid = NetStress.MODID, value = Dist.CLIENT)
public final class ClientNetleakMonitor {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int SAMPLE_INTERVAL_TICKS = 200;

    private static int tickCounter = 0;
    private static boolean announced = false;

    private ClientNetleakMonitor() {
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        if (!announced) {
            announced = true;
            LOGGER.info("[forge_netstress] client monitor active. This is part of a TEST TOOL that "
                    + "generates artificial network traffic per whatever scenario is active server-side; "
                    + "it periodically samples the JVM 'direct' NIO buffer pool so any growth is visible "
                    + "without external tooling. This log reports pool numbers only -- it observes, it "
                    + "does not judge; whether growth is expected depends on which scenario and which "
                    + "Forge build is under test.");
        }

        tickCounter++;
        if (tickCounter < SAMPLE_INTERVAL_TICKS) {
            return;
        }
        tickCounter = 0;

        logDirectPoolStats();
    }

    private static void logDirectPoolStats() {
        List<BufferPoolMXBean> pools = ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class);
        for (BufferPoolMXBean pool : pools) {
            if (!"direct".equals(pool.getName())) {
                continue;
            }
            double usedMb = pool.getMemoryUsed() / (1024.0 * 1024.0);
            double capMb = pool.getTotalCapacity() / (1024.0 * 1024.0);
            long count = pool.getCount();
            LOGGER.info(String.format(
                    "[forge_netstress] direct pool: used=%.2f MB capacity=%.2f MB buffers=%d",
                    usedMb, capMb, count));
            return;
        }
        LOGGER.warn("[forge_netstress] no 'direct' BufferPoolMXBean found on this JVM");
    }
}
