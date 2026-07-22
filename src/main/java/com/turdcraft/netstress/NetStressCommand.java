package com.turdcraft.netstress;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * {@code /netstress list|run <scenario>|stop} — live scenario control, ops
 * only (permission level 2). Registered on the FORGE event bus via
 * {@link RegisterCommandsEvent}, the same annotation-driven subscriber
 * pattern used by {@link ServerTickHandler} and {@link ClientNetleakMonitor}.
 *
 * All three subcommands only ever touch {@link ServerTickHandler#ACTIVE}
 * and {@link Scenarios} — adding a scenario to the registry makes it
 * immediately runnable here with no changes to this class.
 *
 * Note for future maintainers: vanilla command classes
 * ({@code net.minecraft.commands.*}) ship SRG-obfuscated in the Forge
 * compile jars; {@code build.sh}'s FART deobfuscation pass (see its
 * comments) is what makes {@code Commands} / {@code CommandSourceStack} /
 * {@code SharedSuggestionProvider} resolve to official names at compile
 * time here. If a future change references another vanilla command class
 * that fails to resolve, it needs to come from that same deobfuscated jar,
 * not the raw slim/SRG one.
 */
@Mod.EventBusSubscriber(modid = NetStress.MODID)
public final class NetStressCommand {

    private NetStressCommand() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("netstress")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("list")
                        .executes(NetStressCommand::runList))
                .then(Commands.literal("stop")
                        .executes(NetStressCommand::runStop))
                .then(Commands.literal("run")
                        .then(Commands.argument("scenario", StringArgumentType.word())
                                .suggests((ctx, builder) ->
                                        SharedSuggestionProvider.suggest(Scenarios.names(), builder))
                                .executes(NetStressCommand::runRun))));
    }

    private static int runList(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        Scenario active = ServerTickHandler.ACTIVE;
        String activeName = active == null ? "(none -- stopped)" : active.name();
        source.sendSuccess(() -> Component.literal("[forge_netstress] active: " + activeName), false);
        for (String name : Scenarios.names()) {
            Scenario scenario = Scenarios.byName(name);
            String marker = scenario.equals(active) ? " *" : "";
            source.sendSuccess(() -> Component.literal(
                    "[forge_netstress]  - " + name + marker + ": " + scenario.description()), false);
        }
        return 1;
    }

    private static int runStop(CommandContext<CommandSourceStack> ctx) {
        ServerTickHandler.ACTIVE = null;
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[forge_netstress] stopped. Traffic generation halted -- note this only stops new "
                        + "traffic; any already-leaked buffer pool growth will not drain, which is "
                        + "expected and part of what the tool is demonstrating."), true);
        return 1;
    }

    private static int runRun(CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "scenario");
        Scenario scenario = Scenarios.byName(name);
        if (scenario == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "[forge_netstress] unknown scenario '" + name + "' -- see /netstress list"));
            return 0;
        }
        ServerTickHandler.ACTIVE = scenario;
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[forge_netstress] running: " + scenario.name() + " -- " + scenario.description()), true);
        return 1;
    }
}
