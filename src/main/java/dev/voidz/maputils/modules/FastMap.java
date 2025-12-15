package dev.voidz.maputils.modules;

import dev.voidz.maputils.MapUtils;
import meteordevelopment.meteorclient.events.entity.player.InteractBlockEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.item.FilledMapItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

public class FastMap extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> autoSwapBack = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-swap-back")
        .description("Automatically swap back to the map after placing.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> onlyOnBlocks = sgGeneral.add(new BoolSetting.Builder()
        .name("only-on-blocks")
        .description("Only trigger when clicking on a valid block face.")
        .defaultValue(true)
        .build()
    );

    private boolean isPlacing = false;
    private int previousSlot = -1;

    public FastMap() {
        super(MapUtils.CATEGORY, "fast-map", "Silently swaps to item frame when placing maps, then places both automatically.");
    }

    @EventHandler
    private void onInteractBlock(InteractBlockEvent event) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;
        if (isPlacing) return;

        ItemStack mainHand = mc.player.getMainHandStack();

        // Check if holding a filled map
        if (!(mainHand.getItem() instanceof FilledMapItem)) return;

        // Find item frame in hotbar
        FindItemResult itemFrameResult = InvUtils.findInHotbar(Items.ITEM_FRAME, Items.GLOW_ITEM_FRAME);
        if (!itemFrameResult.found()) return;

        BlockHitResult hitResult = event.result;

        // Validate block face if setting enabled
        if (onlyOnBlocks.get()) {
            Direction side = hitResult.getSide();
            if (side == Direction.UP || side == Direction.DOWN) return;
        }

        // Cancel the original event
        event.cancel();

        isPlacing = true;
        previousSlot = mc.player.getInventory().getSelectedSlot();

        try {
            // Silent swap to item frame
            int frameSlot = itemFrameResult.slot();

            // Send packet to place item frame without visually switching
            InvUtils.swap(frameSlot, true);

            // Place the item frame
            mc.getNetworkHandler().sendPacket(new PlayerInteractBlockC2SPacket(
                Hand.MAIN_HAND,
                hitResult,
                0
            ));

            // Swap back silently
            InvUtils.swapBack();

            // Small delay then place the map on the frame
            // The item frame entity needs a tick to spawn
            mc.execute(() -> {
                // Place the map (right click on the item frame location)
                BlockPos framePos = hitResult.getBlockPos().offset(hitResult.getSide());
                BlockHitResult mapHitResult = new BlockHitResult(
                    hitResult.getPos(),
                    hitResult.getSide(),
                    framePos,
                    false
                );

                mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, mapHitResult);

                if (autoSwapBack.get() && previousSlot != -1) {
                    InvUtils.swap(previousSlot, false);
                }

                isPlacing = false;
                previousSlot = -1;
            });

        } catch (Exception e) {
            MapUtils.LOG.error("FastMap error: ", e);
            isPlacing = false;
            previousSlot = -1;
        }
    }

    @Override
    public void onDeactivate() {
        isPlacing = false;
        previousSlot = -1;
    }
}
