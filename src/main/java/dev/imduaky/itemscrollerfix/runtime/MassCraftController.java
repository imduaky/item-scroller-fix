package dev.imduaky.itemscrollerfix.runtime;

import dev.imduaky.itemscrollerfix.mixin.ContainerSetContentPacketAccessor;
import dev.imduaky.itemscrollerfix.mixin.ContainerSetSlotPacketAccessor;
import fi.dy.masa.itemscroller.config.Configs;
import fi.dy.masa.itemscroller.config.Hotkeys;
import fi.dy.masa.itemscroller.recipes.CraftingHandler;
import fi.dy.masa.itemscroller.recipes.CraftingHandler.SlotRange;
import fi.dy.masa.itemscroller.recipes.RecipePattern;
import fi.dy.masa.itemscroller.recipes.RecipeStorage;
import fi.dy.masa.itemscroller.util.ClickPacketBuffer;
import fi.dy.masa.itemscroller.util.InventoryUtils;
import fi.dy.masa.malilib.util.GuiUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/** Serializes mass-craft actions around authoritative container updates. */
public final class MassCraftController {
    private static final int ACK_TIMEOUT_TICKS = 40;
    private static final int WAIT_NONE = 0;
    private static final int WAIT_RECIPE_RESULT = 1;
    private static final int WAIT_OUTPUT_TAKE = 2;
    private static final int WAIT_EXISTING_RESULT_DROP = 3;

    // At most one crafting/drop operation is in flight per container. This avoids sending
    // another click against a client-side prediction the server has not confirmed yet.
    private static int waitingContainer = -1;
    private static int waitTicks;
    private static int baselineStateId;
    private static int waitPhase = WAIT_NONE;
    private static boolean acknowledged;
    private static boolean disabledUntilRelease;
    private static boolean cursorRecoveryPending;
    private static ItemStack expectedResult = ItemStack.EMPTY;
    private static int outputSlot = 0;
    private static AbstractContainerScreen<?> activeScreen;

    private MassCraftController() {}

    public static boolean tickIfPlayerGridTooSmall(Minecraft mc) {
        if (mc.player == null || mc.gameMode == null
                || (!Hotkeys.MASS_CRAFT.getKeybind().isKeybindHeld()
                && !Configs.Generic.MASS_CRAFT_HOLD.getBooleanValue())
                || !(GuiUtils.getCurrentScreen() instanceof AbstractContainerScreen<?> screen)
                || screen.getMenu() != mc.player.containerMenu
                || Configs.GUI_BLACKLIST.contains(screen.getClass().getName())) {
            return false;
        }

        Slot output = CraftingHandler.getFirstCraftingOutputSlotForGui(screen);
        SlotRange grid = output != null ? CraftingHandler.getCraftingGridSlots(screen, output) : null;
        RecipePattern recipe = RecipeStorage.getInstance().getSelectedRecipe();
        if (grid == null || output == null || recipe.getResult().isEmpty()
                || grid.getSlotCount() >= recipe.countRecipeItems()) {
            return false;
        }

        // The player inventory has a 2x2 grid. Preserve Item Scroller's small-grid stop,
        // but still allow the selected recipe's existing output to be discarded.
        boolean wasBufferingClicks = ClickPacketBuffer.shouldBufferClickPackets();
        if (Configs.Generic.RATE_LIMIT_CLICK_PACKETS.getBooleanValue()) {
            ClickPacketBuffer.setShouldBufferClickPackets(true);
        }
        try {
            tick(mc, screen, grid, output, recipe, true);
        } finally {
            ClickPacketBuffer.setShouldBufferClickPackets(wasBufferingClicks);
        }
        return true;
    }

    public static void tick(Minecraft mc,
                            AbstractContainerScreen<?> screen,
                            SlotRange grid,
                            Slot output,
                            RecipePattern recipe) {
        tick(mc, screen, grid, output, recipe, false);
    }

    private static void tick(Minecraft mc,
                             AbstractContainerScreen<?> screen,
                             SlotRange grid,
                             Slot output,
                             RecipePattern recipe,
                             boolean inventoryOnly) {
        if (mc.player == null || mc.gameMode == null || screen.getMenu() != mc.player.containerMenu) {
            reset();
            return;
        }

        int container = screen.getMenu().containerId;
        activeScreen = screen;

        // Only recover a cursor stack if our own ingredient fill left it there. Never move an
        // item the player intentionally picked up before starting mass craft.
        if (!screen.getMenu().getCarried().isEmpty()) {
            if (cursorRecoveryPending && waitingContainer == -1) {
                InventoryUtils.tryClearCursor(screen);
                cursorRecoveryPending = !screen.getMenu().getCarried().isEmpty();
            }
            if (!screen.getMenu().getCarried().isEmpty()) {
                disabledUntilRelease = true;
                return;
            }
        }
        if (disabledUntilRelease) {
            if (acknowledged && screen.getMenu().getCarried().isEmpty()) {
                disabledUntilRelease = false;
                acknowledged = false;
            } else {
                return;
            }
        }

        if (waitingContainer != -1) {
            if (waitingContainer != container) {
                reset();
                return;
            }
            if (++waitTicks > ACK_TIMEOUT_TICKS) {
                // Stop rather than blindly resend a click when the server gives no update.
                // onItemScrollerTickEnd clears this after the user releases the hotkey.
                disabledUntilRelease = true;
                waitingContainer = -1;
                waitPhase = WAIT_NONE;
                acknowledged = false;
                return;
            } else if (acknowledged) {
                waitTicks = 0;
                acknowledged = false;
                if (waitPhase == WAIT_RECIPE_RESULT) {
                    if (output.hasItem() && InventoryUtils.areStacksEqual(output.getItem(), expectedResult)) {
                        baselineStateId = screen.getMenu().getStateId();
                        dropCraftedResult(screen, output.index);
                        waitPhase = WAIT_OUTPUT_TAKE;
                    } else {
                        // The server accepted a recipe placement/update but ingredients are still
                        // incomplete. Re-issue the recipe request after this authoritative update;
                        // continuous replenishment can then complete the grid without a timeout.
                        waitingContainer = -1;
                        waitPhase = WAIT_NONE;
                    }
                    if (waitingContainer != -1) return;
                }
                if (waitPhase == WAIT_EXISTING_RESULT_DROP) {
                    // A drop can update only one inventory slot at a time. Re-scan on the next
                    // tick so every matching backpack stack is removed before crafting resumes.
                    waitingContainer = -1;
                    waitPhase = WAIT_NONE;
                    return;
                }
                if (waitPhase == WAIT_OUTPUT_TAKE) {
                    if (output.hasItem() && InventoryUtils.areStacksEqual(output.getItem(), expectedResult)) {
                        // The server produced the next result after the previous drop. Drop one
                        // full result stack and wait for its authoritative update before continuing.
                        baselineStateId = screen.getMenu().getStateId();
                        dropCraftedResult(screen, output.index);
                        return;
                    }
                    waitingContainer = -1;
                    waitPhase = WAIT_NONE;
                }
            }
            else return;
        }

        if (grid == null || output == null || recipe == null || recipe.getResult().isEmpty()) {
            disabledUntilRelease = true;
            return;
        }

        // Let server updates reach the menu so the next iteration is based on server state.
        InventoryUtils.bufferInvUpdates = false;
        expectedResult = recipe.getResult().copy();
        waitingContainer = container;
        waitTicks = 0;
        baselineStateId = screen.getMenu().getStateId();
        outputSlot = output.index;
        waitPhase = WAIT_NONE;

        // Match Item Scroller's throw-results action on every iteration. This also handles
        // output stacks that arrive in the inventory after a previous server update.
        if (dropExistingRecipeResults(mc, screen)) {
            waitPhase = WAIT_EXISTING_RESULT_DROP;
            return;
        }

        if (inventoryOnly) {
            waitingContainer = -1;
            waitPhase = WAIT_NONE;
            return;
        }

        if (output.hasItem() && InventoryUtils.areStacksEqual(output.getItem(), expectedResult)) {
            dropCraftedResult(screen, output.index);
            waitPhase = WAIT_OUTPUT_TAKE;
        } else {
            if (recipe.getNetworkRecipeId() != null) {
                // Match Item Scroller's recipe-book path: retain ingredients already matching
                // this recipe, return mismatches to inventory, and let the server place items.
                InventoryUtils.clearFirstCraftingGridOfItems(recipe, screen, true);
                // The recipe-book request lets the server determine ingredient availability.
                mc.gameMode.handlePlaceRecipe(container, recipe.getNetworkRecipeId(), true);
            } else {
                // Fallback for recipes without a server recipe-book id.
                InventoryUtils.tryMoveItemsToFirstCraftingGrid(recipe, screen, true);
                if (!screen.getMenu().getCarried().isEmpty()) {
                    cursorRecoveryPending = true;
                    InventoryUtils.tryClearCursor(screen);
                    cursorRecoveryPending = !screen.getMenu().getCarried().isEmpty();
                }
            }
            waitPhase = WAIT_RECIPE_RESULT;
        }
    }

    public static void onItemScrollerTickEnd() {
        if (GuiUtils.getCurrentScreen() != activeScreen) {
            reset();
        } else if (!Hotkeys.MASS_CRAFT.getKeybind().isKeybindHeld() && waitingContainer == -1) {
            if (disabledUntilRelease || !Configs.Generic.MASS_CRAFT_HOLD.getBooleanValue()) {
                reset();
            }
        }
    }

    public static void onSlotUpdate(ClientboundContainerSetSlotPacket packet) {
        if (waitingContainer == -1) return;
        var view = (ContainerSetSlotPacketAccessor) packet;
        int slot = view.itemscrollerfix$getSlot();
        if (view.itemscrollerfix$getContainerId() == waitingContainer
                && isNewerState(view.itemscrollerfix$getStateId(), baselineStateId)) {
            // Recipe placement may update multiple slots; any newer slot packet confirms that
            // the server processed the request. Output-taking specifically waits for its slot.
            if (waitPhase == WAIT_RECIPE_RESULT) {
                acknowledged = true;
            } else if (waitPhase == WAIT_EXISTING_RESULT_DROP) {
                acknowledged = true;
            } else if (waitPhase == WAIT_OUTPUT_TAKE
                    && slot == outputSlot) {
                acknowledged = true;
            }
        }
    }

    public static void onContainerContent(ClientboundContainerSetContentPacket packet) {
        var view = (ContainerSetContentPacketAccessor) (Object) packet;
        if (waitingContainer != -1
                && view.itemscrollerfix$getContainerId() == waitingContainer
                && isNewerState(view.itemscrollerfix$getStateId(), baselineStateId)) {
            var mc = Minecraft.getInstance();
            if (mc.player != null && mc.player.containerMenu.containerId == waitingContainer) {
                // Full-content packets describe the whole transaction, so the state id and
                // container identity are sufficient confirmation for any active phase.
                acknowledged = outputSlot >= 0
                        && outputSlot < view.itemscrollerfix$getItems().size();
            }
        }
    }

    private static boolean isNewerState(int candidate, int baseline) {
        // State ids are wrapping ints. This serial-number comparison rejects delayed older packets
        // while still accepting the normal transition through Integer.MAX_VALUE to MIN_VALUE.
        return candidate - baseline > 0;
    }

    private static boolean dropExistingRecipeResults(Minecraft mc, AbstractContainerScreen<?> screen) {
        if (mc.player == null) return false;
        boolean dropped = false;
        for (Slot slot : screen.getMenu().slots) {
            if (slot.container == mc.player.getInventory()
                    && slot.getContainerSlot() >= 0
                    && slot.getContainerSlot() < 36
                    && slot.hasItem()
                    && InventoryUtils.areStacksEqual(slot.getItem(), expectedResult)) {
                dropCraftedResult(screen, slot.index);
                dropped = true;
            }
        }
        return dropped;
    }

    private static void dropCraftedResult(AbstractContainerScreen<?> screen, int slot) {
        if (Configs.Generic.CARPET_CTRL_Q_CRAFTING.getBooleanValue()) {
            // Item Scroller's Carpet compatibility mode intentionally drops one item per action.
            InventoryUtils.dropStack(screen, slot);
        } else {
            // Match the normal Item Scroller behavior: drain matching results in one burst.
            InventoryUtils.dropStacksWhileHasItem(screen, slot, expectedResult);
        }
    }

    private static void reset() {
        waitingContainer = -1;
        waitTicks = 0;
        baselineStateId = 0;
        waitPhase = WAIT_NONE;
        acknowledged = false;
        disabledUntilRelease = false;
        cursorRecoveryPending = false;
        expectedResult = ItemStack.EMPTY;
        outputSlot = 0;
        activeScreen = null;
    }
}
