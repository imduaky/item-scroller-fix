# Item Scroller Crafting Fix

面向 Minecraft 1.21.11 Fabric 客户端附属 mod。它通过 Mixin 注入 Item Scroller 的快速合成流程，在复用原配方整理和物品移动能力的基础上，按服务端容器更新推进连续合成。

## 背景：原版连续合成的行为和问题

Item Scroller 在每次客户端 tick 中，按配置选择配方书、swaps-only 或 fallback 路径，并循环执行配置次数。进入合成逻辑前，它会启用容器更新缓冲，循环完成后再回放暂存的服务端更新：

```java
InventoryUtils.bufferInvUpdates = true;

for (int i = 0; i < limit; i++) {
    mc.gameMode.handlePlaceRecipe(menu.containerId, recipe.getNetworkRecipeId(), true);
    InventoryUtils.shiftClickSlot(gui, outputSlot.index);
    InventoryUtils.dropStack(gui, outputSlot.index);
}

InventoryUtils.bufferInvUpdates = false;
// 回放 invUpdatesBuffer 中暂存的服务端包
```

这种方式在服务端响应快、材料稳定时效率很高，但循环会在一次客户端执行中发出多轮操作，期间看不到逐轮应用后的服务端状态。若材料通过子区块合成站按比例分批进入背包，或服务端更新较慢，客户端可能在材料到齐前继续在服务端未回应的情况下点击，导致配方未摆全、鼠标光标残留，或连续合成进入错误状态。

同时原版配方书路径还会在工作台配方不匹配时清理配方格，然后向服务端请求摆放配方：

```java
if (recipe.getVanillaRecipe() != null
        && !recipe.getVanillaRecipe().matches(craftingInv.asCraftInput(), mc.level)) {
    for (int slotNum = range.getFirst(); slotNum <= range.getLast(); slotNum++) {
        InventoryUtils.shiftClickSlot(gui, slotNum);
        if (gui.getMenu().getSlot(slotNum).hasItem()) {
            InventoryUtils.dropStack(gui, slotNum);
        }
    }
}

mc.gameMode.handlePlaceRecipe(
        gui.getMenu().containerId,
        recipe.getNetworkRecipeId(),
        true);
```

清理时先尝试把格子里的材料 shift-click 回玩家背包；仍无法移走的材料才丢弃。请求由服务端处理材料分配，因此背包材料暂时不足时，后续新材料到达也可能让配方重新摆齐。这个逻辑是好的，可以保留，但它需要每次都和服务端确认，不能和客户端的操作混在一起。

## 修改过程

### 1. 接管三条连续合成路径

Item Scroller 会根据配置和服务端支持情况进入不同方法。本 mod 在方法开头取消原有批处理逻辑，并转给同一个控制器：

```java
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
```

`onTickFallback` 和 `onTickSwapsOnly` 也采用同样的注入。这样无论用户使用哪种 Item Scroller 合成设置，服务端确认逻辑都一致。

### 2. 复用原配方书和材料清理逻辑

有服务端配方 ID 时，没有重写配方匹配规则，而是保留当前配方中已经正确摆好的材料、清理不匹配材料，然后请求服务端填充：

```java
InventoryUtils.clearFirstCraftingGridOfItems(recipe, screen, true);
mc.gameMode.handlePlaceRecipe(container, recipe.getNetworkRecipeId(), true);
```

`true` 表示只清理不匹配当前配方的格子。`clearFirstCraftingGridOfItems` 会尝试把这些物品放回背包；背包满且物品无法移走时，按 Item Scroller 自己的处理方式丢弃。没有服务端 recipe ID 时，才退回 Item Scroller 本地 `tryMoveItemsToFirstCraftingGrid` 补充合成材料的方法。

### 3. 用服务端包确认每一步

控制器一次只保留一个待确认容器操作。它观察 原版Minecraft 已应用的单槽更新和完整容器更新：

```java
@Inject(method = "handleContainerSetSlot", at = @At("RETURN"))
private void itemscrollerfix$observeSlotUpdate(
        ClientboundContainerSetSlotPacket packet,
        CallbackInfo ci) {
    MassCraftController.onSlotUpdate(packet);
}
```

确认时要求容器 ID 与当前工作台一致，并且要求 state ID 比操作前记录的值新。配方摆放可能更新多个槽，所以配方阶段任意有效槽位更新都可以确认服务端已处理请求；丢弃产物阶段则等待输出槽更新。收到确认后再决定是丢出产物、重新请求缺少的材料，还是结束当前一轮合成请求。

```java
if (waitingContainer != -1) {
    if (++waitTicks > ACK_TIMEOUT_TICKS) {
        disabledUntilRelease = true;
        waitingContainer = -1;
        return;
    }

    if (!acknowledged) return;
    // 依据 WAIT_RECIPE_RESULT / WAIT_OUTPUT_TAKE 处理已确认状态
}
```

如果服务器确认了配方格更新，但结果槽仍没有当前配方产物，控制器会再次请求摆放配方。这样材料短缺时可以等待后续容器更新与补货，而不把第一次缺料直接当作永久失败。若 40 tick 内完全没有服务器更新，则暂停并等待用户松开热键，避免无限重发未确认的点击。

容器 state ID 会回绕，故用差值比较新旧，而不是直接比较数值大小：

```java
private static boolean isNewerState(int candidate, int baseline) {
    return candidate - baseline > 0;
}
```

### 4. 清理已有产物并保持快速丢弃

开始时扫描玩家背包，只丢弃和当前选择配方结果相同的物品，其他物品不动。工作台中刚产出的物品也按 Item Scroller 原有路径丢弃。Carpet Ctrl+Q 选项保持原有语义：

```java
if (Configs.Generic.CARPET_CTRL_Q_CRAFTING.getBooleanValue()) {
    InventoryUtils.dropStack(screen, slot); // Carpet 兼容模式：每次丢一个
} else {
    InventoryUtils.dropStacksWhileHasItem(screen, slot, expectedResult); // 快速丢完
}
```

未启用 Carpet Ctrl+Q 时，`dropStacksWhileHasItem` 会在一次调用中快速丢出该槽位所有匹配产物；启用时则按兼容模式单次丢弃。丢弃后控制器等待服务端容器状态，再处理工作台是否出现下一份产物。

## 状态机和代码位置

```text
检查当前界面和光标
        ↓
清理背包中已有的当前配方产物（每次按住合成键只做一次）
        ↓
保留匹配材料 / 清理不匹配材料 / 请求配方摆放
        ↓ 等待服务端容器更新
结果可用？ ── 否 → 再次请求配方摆放
        │ 是
        ↓
丢弃结果 → 等待服务端更新 → 处理下一份结果或补料
```

- `KeybindCallbacksMixin`：接管 Item Scroller 的三种连续合成方法，并清理按键释放后的状态。
- `ClientPacketListenerMixin`：在 Vanilla 应用服务器容器包后通知控制器。
- `ContainerSetSlotPacketAccessor` / `ContainerSetContentPacketAccessor`：读取包中的容器 ID、state ID、槽位和物品列表。
- `MassCraftController`：状态机、服务器配方请求、已有产物清理、快速丢弃和超时恢复。

## 支持范围、安装与构建

- 当前分支只支持 Minecraft 1.21.11；26.2 和 26.3 分别维护在 `je/26.2` 和 `je/26.3`。
- Fabric 客户端 mod；运行需要 Fabric Loader、Item Scroller 0.30.8 和 MaLiLib 0.27.19。
- 使用 Java 21、Gradle Wrapper 和 Fabric Loom 1.17.21 构建。
- GitHub Actions 会在 push、PR 和手动触发时构建；推送 `vX.Y.Z` tag 会创建 GitHub Release，并附上构建 jar。
- 将 jar 放入 `mods` 即启用；删除 jar 即停用，没有游戏内开关。

```sh
./gradlew build
```

构建产物位于 `build/libs/`。

当前版本参数位于 `gradle.properties`，GitHub Actions 的构建版本矩阵位于 `.github/workflows/build.yml`。添加 Minecraft 版本时，在矩阵中补充对应的 Minecraft、Java、Loader、Item Scroller 和 MaLiLib 版本即可；Fabric 元数据和产物文件名会跟随这些参数变化。

## 使用和调研参考

- [Item Scroller](https://github.com/sakura-ryoko/itemscroller)：运行时依赖；注入目标和复用的配方/物品处理实现。
- [MaLiLib](https://github.com/sakura-ryoko/malilib)：Item Scroller 的运行依赖。
- [Fabric Loader](https://github.com/FabricMC/fabric-loader)：客户端 mod 加载器。
- [QuickCraft](https://github.com/qk-yiyihehe/quickcraft)：行为调研参考；没有复制其实现代码。
- [ItemScroller Crafting Fix](https://github.com/andrews54757/itemscroller-crafting-fix)：旧版留料方式调研参考；没有复制其实现代码。
