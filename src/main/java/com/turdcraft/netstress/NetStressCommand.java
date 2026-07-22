package com.turdcraft.netstress;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.logging.LogUtils;

import net.minecraft.commands.CommandSourceStack;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import org.slf4j.Logger;

/**
 * {@code /netstress list|run <scenario>|stop} — live scenario control.
 * Registered on the FORGE event bus via {@link RegisterCommandsEvent}, the
 * same annotation-driven subscriber pattern used by {@link
 * ServerTickHandler} and {@link ClientNetleakMonitor}.
 *
 * All three subcommands only ever touch {@link ServerTickHandler#ACTIVE}
 * and {@link Scenarios} — adding a scenario to the registry makes it
 * immediately runnable here with no changes to this class.
 *
 * <p><b>Built on raw Brigadier only — no {@code net.minecraft.commands.Commands}.</b>
 * Forge 1.20.1's distributed compile jars carry vanilla members (including
 * every method on {@code net.minecraft.commands.Commands} /
 * {@code CommandSourceStack} / {@code SharedSuggestionProvider}) under SRG
 * names on disk; the "official" Mojang names this build's FART
 * deobfuscation pass (see {@code build.sh}) produces only exist in our
 * *compile-time* jar, not in the Forge runtime's own jars. A direct call to
 * any method declared on a {@code net.minecraft} class therefore compiles
 * clean here but throws {@code NoSuchMethodError} the instant it runs on a
 * real dedicated server (this is exactly how v0.1 crashed: {@code
 * Commands.literal(String)} at line 41). {@code CommandSourceStack} is
 * still referenced here, but only as Brigadier's generic {@code S} type
 * parameter — its type is resolved at compile time from the deobfuscated
 * jar (needed for generics/type-checking), but none of its *methods* are
 * ever invoked, so no vanilla Methodref is ever emitted for it. See {@code
 * build.sh}'s bytecode gate, which asserts this mechanically for every
 * class in the jar.
 *
 * <p>No permission gate ({@code CommandSourceStack.hasPermission} is a
 * vanilla instance method — off-limits) and no {@code sendSuccess}/{@code
 * sendFailure} feedback (same reason) — every subcommand instead logs its
 * outcome via {@link #LOGGER}, visible in the server console/log. This is
 * a throwaway-server test tool; see TESTING.md. Tab-completion uses
 * Brigadier's own {@code SuggestionsBuilder} directly (pure Brigadier API,
 * not vanilla's {@code SharedSuggestionProvider} helper).
 */
@Mod.EventBusSubscriber(modid = NetStress.MODID)
public final class NetStressCommand {

    private static final Logger LOGGER = LogUtils.getLogger();

    private NetStressCommand() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                LiteralArgumentBuilder.<CommandSourceStack>literal("netstress")
                        .then(LiteralArgumentBuilder.<CommandSourceStack>literal("list")
                                .executes(NetStressCommand::runList))
                        .then(LiteralArgumentBuilder.<CommandSourceStack>literal("stop")
                                .executes(NetStressCommand::runStop))
                        .then(LiteralArgumentBuilder.<CommandSourceStack>literal("run")
                                .then(RequiredArgumentBuilder
                                        .<CommandSourceStack, String>argument("scenario", StringArgumentType.word())
                                        .suggests((ctx, builder) -> {
                                            for (String name : Scenarios.names()) {
                                                builder.suggest(name);
                                            }
                                            return builder.buildFuture();
                                        })
                                        .executes(NetStressCommand::runRun))));
    }

    private static int runList(CommandContext<CommandSourceStack> ctx) {
        Scenario active = ServerTickHandler.ACTIVE;
        String activeName = active == null ? "(none -- stopped)" : active.name();
        LOGGER.info("[forge_netstress] active: {}", activeName);
        for (String name : Scenarios.names()) {
            Scenario scenario = Scenarios.byName(name);
            String marker = scenario.equals(active) ? " *" : "";
            LOGGER.info("[forge_netstress]  - {}{}: {}", name, marker, scenario.description());
        }
        return 1;
    }

    private static int runStop(CommandContext<CommandSourceStack> ctx) {
        ServerTickHandler.ACTIVE = null;
        LOGGER.info("[forge_netstress] stopped. Traffic generation halted -- note this only stops new "
                + "traffic; any already-leaked buffer pool growth will not drain, which is expected and "
                + "part of what the tool is demonstrating.");
        return 1;
    }

    private static int runRun(CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "scenario");
        Scenario scenario = Scenarios.byName(name);
        if (scenario == null) {
            LOGGER.warn("[forge_netstress] unknown scenario '{}' -- see /netstress list", name);
            return 0;
        }
        ServerTickHandler.ACTIVE = scenario;
        LOGGER.info("[forge_netstress] running: {} -- {}", scenario.name(), scenario.description());
        return 1;
    }
}
