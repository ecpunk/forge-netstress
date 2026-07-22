package com.turdcraft.netstress;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

/**
 * Registers the single SimpleChannel and its single message type. Loaded
 * identically on both dedicated server and client — SimpleChannel/message
 * registration is dist-agnostic in Forge, same as any real mod's network
 * setup.
 */
final class NetworkHandler {

    private static final String PROTOCOL_VERSION = "1";

    static final SimpleChannel CHANNEL = NetworkRegistry.ChannelBuilder
            .named(new ResourceLocation(NetStress.MODID, "test"))
            .networkProtocolVersion(() -> PROTOCOL_VERSION)
            .clientAcceptedVersions(PROTOCOL_VERSION::equals)
            .serverAcceptedVersions(PROTOCOL_VERSION::equals)
            .simpleChannel();

    private NetworkHandler() {
    }

    static void register() {
        CHANNEL.messageBuilder(TestPayload.class, 0)
                .encoder(TestPayload::encode)
                .decoder(TestPayload::decode)
                .consumerMainThread(TestPayload::handle)
                .add();
    }
}
