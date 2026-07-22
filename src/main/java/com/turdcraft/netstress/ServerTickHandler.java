package com.turdcraft.netstress;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;

/**
 * Server-side traffic generator: every server tick (END phase), sends the
 * active {@link Scenario}'s packets to each currently-connected player via
 * {@code PacketDistributor.PLAYER}.
 *
 * <p>Connected players are tracked in {@link #PLAYERS}, a registry
 * maintained purely from Forge's own {@link PlayerEvent.PlayerLoggedInEvent}
 * / {@link PlayerEvent.PlayerLoggedOutEvent} — <b>not</b> by walking {@code
 * event.getServer().getPlayerList().getPlayers()} as v0.1 did. That walk
 * calls three methods declared on {@code net.minecraft} classes ({@code
 * MinecraftServer.getPlayerList()}, {@code PlayerList.getPlayers()}), which
 * — like {@code Commands.literal(String)} in the command-registration crash
 * this version fixes — resolve at compile time against our FART-deobfuscated
 * jar but do not exist under those names in the Forge runtime's own SRG-named
 * jars, so they would have thrown {@code NoSuchMethodError} on the very
 * first server tick even after the command-registration fix. {@code
 * PlayerEvent#getEntity()} is safe to call here because it is declared on
 * {@code net.minecraftforge.event.entity.player.PlayerEvent} itself (Forge
 * API, overriding {@code EntityEvent#getEntity()} — also Forge API) — the
 * fact that it *returns* a vanilla {@code Player} reference is irrelevant;
 * only invoking a method *declared on* a {@code net.minecraft} class is
 * unsafe, and storing/casting a vanilla-typed reference never does that.
 *
 * <p>{@link #ACTIVE} is the one piece of live-mutable state in the harness
 * -- {@link NetStressCommand} swaps it in response to {@code /netstress run
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

    /** Currently-connected players, maintained via login/logout events
     *  rather than a live vanilla player-list walk (see class javadoc). A
     *  concurrent set because logins/logouts land on the network/command
     *  thread while the tick loop reads it on the server tick thread. */
    private static final Set<ServerPlayer> PLAYERS = ConcurrentHashMap.newKeySet();

    private ServerTickHandler() {
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        PLAYERS.add((ServerPlayer) event.getEntity());
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        PLAYERS.remove((ServerPlayer) event.getEntity());
    }

    /** Hygiene: drop all tracked players when the server shuts down, so
     *  nothing outlives the server instance across a hypothetical embedded
     *  reload. */
    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        PLAYERS.clear();
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
        for (ServerPlayer player : PLAYERS) {
            for (int i = 0; i < scenario.packetsPerTick(); i++) {
                NetworkHandler.CHANNEL.send(
                        PacketDistributor.PLAYER.with(() -> player),
                        new TestPayload(scenario.payloadBytes()));
            }
        }
    }
}
