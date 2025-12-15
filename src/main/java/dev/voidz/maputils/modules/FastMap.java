package dev.voidz.maputils.modules;

import dev.voidz.maputils.MapUtils;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
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
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

public class FastMap extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> placeDelay = sgGeneral.add(new DoubleSetting.Builder()
        .name("place-delay")
        .description("Delay between placements in milliseconds.")
        .defaultValue(50.0)
        .min(0.0)
        .sliderRange(0.0, 500.0)
        .build()
    );

    private final Setting<Boolean> silentSwap = sgGeneral.add(new BoolSetting.Builder()
        .name("silent-swap")
        .description("Swap to item frame without visually changing hotbar slot (packet-based).")
        .defaultValue(false)
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

    private long lastPlaceTime = 0;
    private boolean wasPressed = false;
    
    // For delayed map placement
    private boolean pendingMapPlace = false;
    private BlockHitResult pendingHitResult = null;
    private long mapPlaceTime = 0;
    private int originalSlot = -1;

    public FastMap() {
        super(MapUtils.CATEGORY, "fast-map", "Hold a map and click to place item frame + map in one action.");
    }

    @Override
    public void onActivate() {
        lastPlaceTime = 0;
        wasPressed = false;
        pendingMapPlace = false;
        pendingHitResult = null;
        originalSlot = -1;
    }

    @Override
    public void onDeactivate() {
        pendingMapPlace = false;
        pendingHitResult = null;
        originalSlot = -1;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;

        // Handle pending map placement
        if (pendingMapPlace && pendingHitResult != null) {
            long currentTime = System.currentTimeMillis();
            if (currentTime - mapPlaceTime >= 50) { // 50ms delay for frame to spawn
                placeMap(pendingHitResult);
                pendingMapPlace = false;
                pendingHitResult = null;
            }
            return;
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

        // Check delay
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastPlaceTime < placeDelay.get()) {
            wasPressed = isPressed;
            return;
        }

        // On click (not hold)
        if (isPressed && !wasPressed) {
            placeFrameAndMap(blockHitResult, frameResult.slot());
            lastPlaceTime = currentTime;
        }

        wasPressed = isPressed;
    }

    private void placeFrameAndMap(BlockHitResult blockHitResult, int frameSlot) {
        originalSlot = mc.player.getInventory().getSelectedSlot();

        if (silentSwap.get()) {
            // Silent swap: send slot packet without changing client slot
            mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(frameSlot));
            
            // Place item frame
            mc.getNetworkHandler().sendPacket(new PlayerInteractBlockC2SPacket(
                Hand.MAIN_HAND,
                blockHitResult,
                0
            ));

            // Swap back silently
            mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(originalSlot));
        } else {
            // Normal swap: visually switch slots
            InvUtils.swap(frameSlot, false);

            // Place item frame
            mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, blockHitResult);

            // Swap back to map
            InvUtils.swap(originalSlot, false);
        }

        if (swing.get()) {
            mc.player.swingHand(Hand.MAIN_HAND);
        }

        // Schedule map placement
        pendingMapPlace = true;
        pendingHitResult = blockHitResult;
        mapPlaceTime = System.currentTimeMillis();
    }

    private void placeMap(BlockHitResult originalHitResult) {
        if (mc.player == null || mc.world == null) return;

        // Make sure we're holding the map
        ItemStack mainHand = mc.player.getMainHandStack();
        if (!(mainHand.getItem() instanceof FilledMapItem)) return;

        // Calculate where the item frame should be
        BlockPos framePos = originalHitResult.getBlockPos().offset(originalHitResult.getSide());
        
        BlockHitResult mapHitResult = new BlockHitResult(
            framePos.toCenterPos(),
            originalHitResult.getSide().getOpposite(),
            framePos,
            false
        );

        // Place the map
        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, mapHitResult);

        if (swing.get()) {
            mc.player.swingHand(Hand.MAIN_HAND);
        }
    }
}
