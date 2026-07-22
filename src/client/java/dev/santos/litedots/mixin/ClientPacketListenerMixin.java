package dev.santos.litedots.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.CommonPlayerSpawnInfo;

import dev.santos.litedots.world.WorldKeyTracker;

/**
 * Captures the hashed seed + dimension the server sends on login/respawn, per SPEC.md 3.3.
 *
 * <p>Respawn matters as well as login: servers such as Hypixel switch worlds via a respawn packet
 * on the same connection rather than a fresh login. Both handlers are verified (RENDER_NOTES.md
 * 10) to call {@code packet.commonPlayerSpawnInfo()}, whose {@code CommonPlayerSpawnInfo} record
 * exposes {@code seed()} and {@code dimension()} directly (no accessor mixin needed).
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
	@Inject(method = "handleLogin", at = @At("TAIL"))
	private void litedots$onLogin(ClientboundLoginPacket packet, CallbackInfo ci) {
		CommonPlayerSpawnInfo info = packet.commonPlayerSpawnInfo();
		WorldKeyTracker.update(info.seed(), info.dimension().identifier().toString());
	}

	@Inject(method = "handleRespawn", at = @At("TAIL"))
	private void litedots$onRespawn(ClientboundRespawnPacket packet, CallbackInfo ci) {
		CommonPlayerSpawnInfo info = packet.commonPlayerSpawnInfo();
		WorldKeyTracker.update(info.seed(), info.dimension().identifier().toString());
	}
}
