package dev.imduaky.itemscrollerfix.mixin;

import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ClientboundContainerSetSlotPacket.class)
public interface ContainerSetSlotPacketAccessor {
    @Accessor("containerId") int itemscrollerfix$getContainerId();
    @Accessor("stateId") int itemscrollerfix$getStateId();
    @Accessor("slot") int itemscrollerfix$getSlot();
    @Accessor("itemStack") ItemStack itemscrollerfix$getItem();
}
