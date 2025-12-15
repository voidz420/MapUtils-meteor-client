package dev.voidz.maputils.modules;

import dev.voidz.maputils.MapUtils;
import meteordevelopment.meteorclient.events.entity.EntityAddedEvent;
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
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

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
    
    // Track placed frame to put map on it
    private BlockPos waitingForFrameAt = null;
    private int mapSlot = -1;

    public FastMap() {
        super(MapUtils.CATEGORY, "fast-map", "Hold a map and click to place item frame + map. When frame spawns, automatically places the map on it.");
    }

    @Override
    public void onActivate() {
        lastPlaceTime = 0;
        wasPressed = false;
        waitingForFrameAt = null;
        mapSlot = -1;
    }

    @Override
    public void onDeactivate() {
        waitingForFrameAt = null;
        mapSlot = -1;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;

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
        mapSlot = mc.player.getInventory().getSelectedSlot();

        // Remember where we're placing the frame
        waitingForFrameAt = blockHitResult.getBlockPos().offset(blockHitResult.getSide());

        // Swap to item frame
        InvUtils.swap(frameSlot, false);

        // Place item frame normally
        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, blockHitResult);

        if (swing.get()) {
            mc.player.swingHand(Hand.MAIN_HAND);
        }

        // Swap back to map
        InvUtils.swap(mapSlot, false);
    }

    @EventHandler
    private void onEntityAdded(EntityAddedEvent event) {
        if (mc.player == null || mc.world == null) return;
        if (waitingForFrameAt == null) return;

        Entity entity = event.entity;
        
        // Check if it's an item frame
        if (!(entity instanceof ItemFrameEntity frame)) return;

        // Check if it's near where we placed
        double dist = entity.getPos().squaredDistanceTo(Vec3d.ofCenter(waitingForFrameAt));
        if (dist > 2.0) return;

        // Check if frame is empty
        if (!frame.getHeldItemStack().isEmpty()) return;

        // Make sure we're holding a map
        ItemStack mainHand = mc.player.getMainHandStack();
        if (!(mainHand.getItem() instanceof FilledMapItem)) {
            if (mapSlot != -1) {
                InvUtils.swap(mapSlot, false);
                mainHand = mc.player.getMainHandStack();
            }
        }

        if (!(mainHand.getItem() instanceof FilledMapItem)) {
            waitingForFrameAt = null;
            mapSlot = -1;
            return;
        }

        // Interact with the frame to place the map
        mc.interactionManager.interactEntity(mc.player, frame, Hand.MAIN_HAND);

        if (swing.get()) {
            mc.player.swingHand(Hand.MAIN_HAND);
        }

        // Reset state
        waitingForFrameAt = null;
        mapSlot = -1;
    }
}
