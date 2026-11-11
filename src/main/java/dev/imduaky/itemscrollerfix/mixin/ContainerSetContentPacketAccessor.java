package dev.imduaky.itemscrollerfix.mixin;

import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.world.item.ItemStack;
import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ClientboundContainerSetContentPacket.class)
public interface ContainerSetContentPacketAccessor {
    @Accessor("containerId") int itemscrollerfix$getContainerId();
    @Accessor("stateId") int itemscrollerfix$getStateId();
    @Accessor("items") List<ItemStack> itemscrollerfix$getItems();
}
