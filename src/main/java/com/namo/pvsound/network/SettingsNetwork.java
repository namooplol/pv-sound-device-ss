package com.namo.pvsound.network;

import com.namo.pvsound.SettingsHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Packets of the /pvsound setting screen and the cable rules every client needs.
 */
public final class SettingsNetwork {

    static int register(SimpleChannel channel, int id) {
        channel.registerMessage(id++, OpenSettingsS2C.class, OpenSettingsS2C::write, OpenSettingsS2C::read,
                OpenSettingsS2C::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        channel.registerMessage(id++, SaveSettingsC2S.class, SaveSettingsC2S::write, SaveSettingsC2S::read,
                SaveSettingsC2S::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        channel.registerMessage(id++, SettingsActionC2S.class, SettingsActionC2S::write, SettingsActionC2S::read,
                SettingsActionC2S::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        channel.registerMessage(id++, SettingsResultS2C.class, SettingsResultS2C::write, SettingsResultS2C::read,
                SettingsResultS2C::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        channel.registerMessage(id++, CableRulesS2C.class, CableRulesS2C::write, CableRulesS2C::read,
                CableRulesS2C::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        return id;
    }

    public static void send(ServerPlayer player, Object message) {
        PvSoundNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), message);
    }

    public static void broadcast(Object message) {
        PvSoundNetwork.CHANNEL.send(PacketDistributor.ALL.noArg(), message);
    }

    public record OpenSettingsS2C(SettingsData data) {
        static void write(OpenSettingsS2C msg, FriendlyByteBuf buf) {
            msg.data.write(buf);
        }

        static OpenSettingsS2C read(FriendlyByteBuf buf) {
            return new OpenSettingsS2C(SettingsData.read(buf));
        }

        static void handle(OpenSettingsS2C msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> com.namo.pvsound.client.ClientHooks.openSettings(msg.data)));
            ctx.get().setPacketHandled(true);
        }
    }

    public record SaveSettingsC2S(SettingsData data) {
        static void write(SaveSettingsC2S msg, FriendlyByteBuf buf) {
            msg.data.write(buf);
        }

        static SaveSettingsC2S read(FriendlyByteBuf buf) {
            return new SaveSettingsC2S(SettingsData.read(buf));
        }

        static void handle(SaveSettingsC2S msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> SettingsHandler.save(ctx.get().getSender(), msg.data));
            ctx.get().setPacketHandled(true);
        }
    }

    public enum Action { TEST_CIPHER, TEST_TRACK }

    /** TEST_CIPHER: a = url, b = password. TEST_TRACK: a = query. */
    public record SettingsActionC2S(Action action, String a, String b) {
        static void write(SettingsActionC2S msg, FriendlyByteBuf buf) {
            buf.writeEnum(msg.action);
            buf.writeUtf(msg.a, 1024);
            buf.writeUtf(msg.b, 512);
        }

        static SettingsActionC2S read(FriendlyByteBuf buf) {
            return new SettingsActionC2S(buf.readEnum(Action.class), buf.readUtf(1024), buf.readUtf(512));
        }

        static void handle(SettingsActionC2S msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> SettingsHandler.action(ctx.get().getSender(), msg.action, msg.a, msg.b));
            ctx.get().setPacketHandled(true);
        }
    }

    public record SettingsResultS2C(Component message, boolean ok, String activeCipher) {
        static void write(SettingsResultS2C msg, FriendlyByteBuf buf) {
            buf.writeComponent(msg.message);
            buf.writeBoolean(msg.ok);
            buf.writeUtf(msg.activeCipher, 512);
        }

        static SettingsResultS2C read(FriendlyByteBuf buf) {
            return new SettingsResultS2C(buf.readComponent(), buf.readBoolean(), buf.readUtf(512));
        }

        static void handle(SettingsResultS2C msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> com.namo.pvsound.client.ClientHooks.onSettingsResult(msg.message, msg.ok, msg.activeCipher)));
            ctx.get().setPacketHandled(true);
        }
    }

    public record CableRulesS2C(int baseLength, int extensionLength, int maxExtensions) {
        static void write(CableRulesS2C msg, FriendlyByteBuf buf) {
            buf.writeVarInt(msg.baseLength);
            buf.writeVarInt(msg.extensionLength);
            buf.writeVarInt(msg.maxExtensions);
        }

        static CableRulesS2C read(FriendlyByteBuf buf) {
            return new CableRulesS2C(buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
        }

        static void handle(CableRulesS2C msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> com.namo.pvsound.client.ClientHooks.onCableRules(msg.baseLength, msg.extensionLength, msg.maxExtensions)));
            ctx.get().setPacketHandled(true);
        }
    }

    private SettingsNetwork() {
    }
}
