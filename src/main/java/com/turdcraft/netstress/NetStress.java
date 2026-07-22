package com.turdcraft.netstress;

import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

/**
 * forge_netstress
 *
 * A small, scenario-driven Forge network stress harness. It grew out of a
 * minimal reproducer for a proven stock-Forge 1.20.1 (47.4.18) bug --
 * {@code SimpleChannel#networkEventListener} never releases the pooled
 * direct {@code ByteBuf} copy that {@code ClientboundCustomPayloadPacket
 * #getInternalData()} (and its serverbound equivalent) allocates for every
 * custom-payload packet read off the wire -- and that reproducer is now
 * scenario #1 ({@code baseline_4k_20hz}) of this harness.
 *
 * Each {@link Scenario} declares a payload size and a send rate; the
 * active scenario is switched live with {@code /netstress} (see
 * {@link NetStressCommand}) with no restart needed. The server-side
 * traffic generator lives in {@link ServerTickHandler}, the wire format in
 * {@link TestPayload}, channel/message registration in
 * {@link NetworkHandler}, and the client-side buffer-pool gauge in
 * {@link ClientNetleakMonitor}.
 *
 * THIS IS A TEST TOOL. It exists to generate artificial, deliberately
 * heavy network traffic for probing Forge's networking layer -- it is not
 * useful, and should never be installed, on a normal or shared play
 * server. See TESTING.md for the throwaway-server procedure this mod is
 * meant to be run under.
 */
@Mod(NetStress.MODID)
public class NetStress {

    public static final String MODID = "forge_netstress";

    private static final Logger LOGGER = LogUtils.getLogger();

    public NetStress() {
        NetworkHandler.register();
        LOGGER.warn("[{}] loaded. *** THIS IS A TEST TOOL that generates artificial, deliberately "
                + "heavy network traffic. It is NOT for normal play -- do not install it on a shared or "
                + "family server. *** Default-active scenario: 'baseline_4k_20hz' (one 4096-byte "
                + "custom-payload packet per connected player per server tick, ~20/s/player, ~80 "
                + "KB/s/player). Use /netstress list|run <name>|stop (requires op permission) to switch "
                + "or halt scenarios live. Client side logs the JVM direct buffer pool periodically so "
                + "the effect of a scenario is visible without external tooling.", MODID);
    }
}
