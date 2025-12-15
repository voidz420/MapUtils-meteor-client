package dev.voidz.maputils.modules;

import dev.voidz.maputils.MapUtils;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.item.FilledMapItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

public class FastMap extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> placeDelay = sgGeneral.add(new IntSetting.Builder()
        .name("place-delay")
        .description("Delay in ticks between placements.")
        .defaultValue(0)
        .min(0)
        .sliderRange(0, 10)
        .build()
    );

    private final Setting<Boolean> onlyWalls = sgGeneral.add(new BoolSetting.Builder()
        .name("only-walls")
        .description("Only place on wall surfaces (not floor/ceiling).")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> swing = sgGeneral.add(new BoolSetting.Builder()
        .name("swing")
        .description("Swing hand when placing.")
        .defaultValue(true)
        .build()
    );

    private int tickDelay = 0;
    private boolean wasPressed = false;

    public FastMap() {
        super(MapUtils.CATEGORY, "fast-map", "Hold a map and click to silently place item frame + map in one action. Uses offhand swap for Grim bypass.");
    }

    @Override
    public void onActivate() {
        tickDelay = 0;
        wasPressed = false;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;

        if (tickDelay < placeDelay.get()) {
            tickDelay++;
        }

        // Check if holding a filled map in main hand
        ItemStack mainHand = mc.player.getMainHandStack();
        if (!(mainHand.getItem() instanceof FilledMapItem)) {
            wasPressed = false;
            return;
        }

        // Find item frame in hotbar
        FindItemResult frameResult = InvUtils.findInHotbar(Items.ITEM_FRAME, Items.GLOW_ITEM_FRAME);
        if (!frameResult.found()) {
            wasPressed = false;
            return;
        }

        // Check if looking at a block
        HitResult hitResult = mc.getCameraEntity().raycast(mc.player.getBlockInteractionRange(), 0, false);
        if (!(hitResult instanceof BlockHitResult blockHitResult)) {
            wasPressed = false;
            return;
        }

        // Check wall only setting
        if (onlyWalls.get()) {
            Direction side = blockHitResult.getSide();
            if (side == Direction.UP || side == Direction.DOWN) {
                wasPressed = false;
                return;
            }
        }

        // Check if use key is pressed
        boolean isPressed = mc.options.useKey.isPressed();

        if (mc.currentScreen != null) {
            wasPressed = isPressed;
            return;
        }

        // On click (not hold)
        if (isPressed && !wasPressed && tickDelay >= placeDelay.get()) {
            placeFrameAndMap(blockHitResult, frameResult.slot());
            tickDelay = 0;
        }

        wasPressed = isPressed;
    }

    private void placeFrameAndMap(BlockHitResult blockHitResult, int frameSlot) {
        // Save current slot
        int currentSlot = mc.player.getInventory().getSelectedSlot();

        // Step 1: Swap item frame to offhand silently
        mc.player.networkHandler.sendPacket(new PlayerActionC2SPacket(
            PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND,
            BlockPos.ORIGIN,
            Direction.DOWN
        ));

        // Step 2: Swap to item frame slot
        InvUtils.swap(frameSlot, false);

        // Step 3: Swap again (now item frame is in offhand)
        mc.player.networkHandler.sendPacket(new PlayerActionC2SPacket(
            PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND,
            BlockPos.ORIGIN,
            Direction.DOWN
        ));

        // Step 4: Swap back to original slot (map)
        InvUtils.swap(currentSlot, false);

        // Step 5: Place item frame from offhand
        mc.player.networkHandler.sendPacket(new PlayerInteractBlockC2SPacket(
            Hand.OFF_HAND,
            blockHitResult,
            mc.player.currentScreenHandler.getRevision() + 2
        ));

        // Step 6: Swap offhand back (restore original offhand)
        mc.player.networkHandler.sendPacket(new PlayerActionC2SPacket(
            PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND,
            BlockPos.ORIGIN,
            Direction.DOWN
        ));

        if (swing.get()) {
            mc.player.swingHand(Hand.MAIN_HAND);
        }

        // Step 7: Schedule map placement for next tick (frame needs to spawn)
        mc.execute(() -> {
            // Place the map on the item frame
            BlockPos framePos = blockHitResult.getBlockPos().offset(blockHitResult.getSide());
            BlockHitResult mapHitResult = new BlockHitResult(
                framePos.toCenterPos(),
                blockHitResult.getSide().getOpposite(),
                framePos,
                false
            );

            mc.player.networkHandler.sendPacket(new PlayerInteractBlockC2SPacket(
                Hand.MAIN_HAND,
                mapHitResult,
                mc.player.currentScreenHandler.getRevision() + 2
            ));

            if (swing.get()) {
                mc.player.swingHand(Hand.MAIN_HAND);
            }
        });
    }
}
