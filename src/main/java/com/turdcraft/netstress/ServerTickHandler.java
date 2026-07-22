package com.turdcraft.netstress;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;

/**
 * Server-side traffic generator: every server tick (END phase), sends the
 * active {@link Scenario}'s packets to each currently-connected player via
 * {@code PacketDistributor.PLAYER}.
 *
 * {@link #ACTIVE} is the one piece of live-mutable state in the harness --
 * {@link NetStressCommand} swaps it in response to {@code /netstress run
 * <name>} / {@code /netstress stop}, and this handler just reads whatever
 * is there each tick. No scenario details (payload size, rate) are
 * hardcoded here; they all come from the {@link Scenario} itself, so
 * adding a new scenario to {@link Scenarios} never requires touching this
 * class.
 *
 * Defaults to {@code baseline_4k_20hz} (the original #10861 reproducer
 * behavior: one 4096-byte packet per player per tick, ~20/s/player, ~80
 * KB/s/player) so a fresh install with no commands run reproduces exactly
 * what the standalone reproducer did. {@code /netstress stop} sets {@link
 * #ACTIVE} to {@code null}, which this handler treats as "do nothing."
 *
 * Registered automatically by Forge's annotation-driven subscriber scan
 * (default bus = FORGE, i.e. {@code MinecraftForge.EVENT_BUS}); no manual
 * {@code IEventBus.register(...)} call needed.
 */
@Mod.EventBusSubscriber(modid = NetStress.MODID)
public final class ServerTickHandler {

    /** The scenario currently generating traffic, or {@code null} if
     *  stopped. Volatile because it is written from the command-handling
     *  thread and read from the server tick thread. */
    static volatile Scenario ACTIVE = Scenarios.byName("baseline_4k_20hz");

    private ServerTickHandler() {
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Scenario scenario = ACTIVE;
        if (scenario == null) {
            return;
        }
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            for (int i = 0; i < scenario.packetsPerTick(); i++) {
                NetworkHandler.CHANNEL.send(
                        PacketDistributor.PLAYER.with(() -> player),
                        new TestPayload(scenario.payloadBytes()));
            }
        }
    }
}
