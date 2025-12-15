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
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.item.FilledMapItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
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
    private boolean waitingForFrame = false;
    private BlockPos expectedFramePos = null;
    private int mapSlot = -1;
    private int ticksWaited = 0;
    private static final int MAX_WAIT_TICKS = 20; // 1 second max wait

    public FastMap() {
        super(MapUtils.CATEGORY, "fast-map", "Hold a map and click to place item frame + map in one action.");
    }

    @Override
    public void onActivate() {
        lastPlaceTime = 0;
        wasPressed = false;
        resetPendingState();
    }

    @Override
    public void onDeactivate() {
        resetPendingState();
    }

    private void resetPendingState() {
        waitingForFrame = false;
        expectedFramePos = null;
        mapSlot = -1;
        ticksWaited = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;

        // Handle pending map placement - wait for item frame to spawn
        if (waitingForFrame && expectedFramePos != null) {
            ticksWaited++;
            
            // Check if item frame spawned at expected position
            ItemFrameEntity frame = findItemFrameAt(expectedFramePos);
            if (frame != null) {
                // Frame found, place the map
                placeMapOnFrame(frame);
                resetPendingState();
                return;
            }
            
            // Timeout
            if (ticksWaited >= MAX_WAIT_TICKS) {
                resetPendingState();
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
            placeFrame(blockHitResult, frameResult.slot());
            lastPlaceTime = currentTime;
        }

        wasPressed = isPressed;
    }

    private void placeFrame(BlockHitResult blockHitResult, int frameSlot) {
        int currentSlot = mc.player.getInventory().getSelectedSlot();

        // Swap to item frame
        InvUtils.swap(frameSlot, false);

        // Place item frame
        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, blockHitResult);

        if (swing.get()) {
            mc.player.swingHand(Hand.MAIN_HAND);
        }

        // Swap back to map
        InvUtils.swap(currentSlot, false);

        // Set up waiting for frame to spawn
        expectedFramePos = blockHitResult.getBlockPos().offset(blockHitResult.getSide());
        mapSlot = currentSlot;
        waitingForFrame = true;
        ticksWaited = 0;
    }

    private ItemFrameEntity findItemFrameAt(BlockPos pos) {
        Box searchBox = new Box(pos).expand(0.5);
        for (Entity entity : mc.world.getEntities()) {
            if (entity instanceof ItemFrameEntity frame) {
                if (searchBox.contains(frame.getPos())) {
                    // Check if frame is empty (no item in it yet)
                    if (frame.getHeldItemStack().isEmpty()) {
                        return frame;
                    }
                }
            }
        }
        return null;
    }

    private void placeMapOnFrame(ItemFrameEntity frame) {
        if (mc.player == null) return;

        // Make sure we're holding the map
        ItemStack mainHand = mc.player.getMainHandStack();
        if (!(mainHand.getItem() instanceof FilledMapItem)) {
            // Try to swap to map slot if we saved it
            if (mapSlot != -1) {
                InvUtils.swap(mapSlot, false);
            }
        }

        // Interact with the item frame to place the map
        mc.interactionManager.interactEntity(mc.player, frame, Hand.MAIN_HAND);

        if (swing.get()) {
            mc.player.swingHand(Hand.MAIN_HAND);
        }
    }
}
