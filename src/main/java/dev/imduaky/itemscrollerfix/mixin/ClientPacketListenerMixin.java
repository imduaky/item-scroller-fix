package dev.imduaky.itemscrollerfix.mixin;

import dev.imduaky.itemscrollerfix.runtime.MassCraftController;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
    // Observe after vanilla has applied the server's authoritative slot state, so the controller
    // never advances based only on the local result-slot prediction.
    @Inject(method = "handleContainerSetSlot", at = @At("RETURN"))
    private void itemscrollerfix$observeSlotUpdate(ClientboundContainerSetSlotPacket packet, CallbackInfo ci) {
        MassCraftController.onSlotUpdate(packet);
    }

    // Some servers send a full container snapshot instead of individual slot updates.
    @Inject(method = "handleContainerContent", at = @At("RETURN"))
    private void itemscrollerfix$observeContainerContent(ClientboundContainerSetContentPacket packet, CallbackInfo ci) {
        MassCraftController.onContainerContent(packet);
    }
}
