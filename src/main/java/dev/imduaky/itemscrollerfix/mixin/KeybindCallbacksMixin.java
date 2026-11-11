package dev.imduaky.itemscrollerfix.mixin;

import dev.imduaky.itemscrollerfix.runtime.MassCraftController;
import fi.dy.masa.itemscroller.event.KeybindCallbacks;
import fi.dy.masa.itemscroller.recipes.CraftingHandler.SlotRange;
import fi.dy.masa.itemscroller.recipes.RecipePattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = KeybindCallbacks.class, remap = false)
public abstract class KeybindCallbacksMixin {
    // Clear pending state when the mass-craft key is released or the crafting screen closes.
    @Inject(method = "onClientTick", at = @At("RETURN"))
    private void itemscrollerfix$clearAfterMassCraftRelease(Minecraft minecraft, CallbackInfo ci) {
        MassCraftController.onItemScrollerTickEnd();
    }

    // Item Scroller selects one of these implementations based on its settings/server support.
    // Route all of them through the same server-confirmed controller.
    @Inject(method = "onTickRecipeBook", at = @At("HEAD"), cancellable = true)
    private void itemscrollerfix$replaceRecipeBookBurst(
            Minecraft minecraft,
            AbstractContainerScreen<?> screen,
            SlotRange gridSlots,
            Slot outputSlot,
            RecipePattern recipe,
            int limit,
            CallbackInfo ci) {
        MassCraftController.tick(minecraft, screen, gridSlots, outputSlot, recipe);
        ci.cancel();
    }

    @Inject(method = "onTickFallback", at = @At("HEAD"), cancellable = true)
    private void itemscrollerfix$replaceFallbackBurst(
            Minecraft minecraft,
            AbstractContainerScreen<?> screen,
            SlotRange gridSlots,
            Slot outputSlot,
            RecipePattern recipe,
            int limit,
            CallbackInfo ci) {
        MassCraftController.tick(minecraft, screen, gridSlots, outputSlot, recipe);
        ci.cancel();
    }

    @Inject(method = "onTickSwapsOnly", at = @At("HEAD"), cancellable = true)
    private void itemscrollerfix$replaceSwapBurst(
            Minecraft minecraft,
            AbstractContainerScreen<?> screen,
            SlotRange gridSlots,
            Slot outputSlot,
            RecipePattern recipe,
            int limit,
            CallbackInfo ci) {
        MassCraftController.tick(minecraft, screen, gridSlots, outputSlot, recipe);
        ci.cancel();
    }
}
