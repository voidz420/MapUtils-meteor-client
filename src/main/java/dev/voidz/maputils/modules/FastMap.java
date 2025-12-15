package dev.voidz.maputils.modules;

import dev.voidz.maputils.MapUtils;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.item.FilledMapItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
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

    private final Setting<Integer> mapDelay = sgGeneral.add(new IntSetting.Builder()
        .name("map-delay")
        .description("Ticks to wait before placing map after frame (increase if map doesn't place).")
        .defaultValue(2)
        .min(1)
        .sliderRange(1, 10)
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
    private int ticksToWait = 0;
    private BlockPos framePos = null;
    private int savedMapSlot = -1;

    public FastMap() {
        super(MapUtils.CATEGORY, "fast-map", "Hold a map and click to place item frame + map in one action.");
    }

    @Override
    public void onActivate() {
        lastPlaceTime = 0;
        wasPressed = false;
        ticksToWait = 0;
        framePos = null;
        savedMapSlot = -1;
    }

    @Override
    public void onDeactivate() {
        ticksToWait = 0;
        framePos = null;
        savedMapSlot = -1;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;

        // Handle pending map placement
        if (ticksToWait > 0) {
            ticksToWait--;
            if (ticksToWait == 0 && framePos != null) {
                tryPlaceMap();
                framePos = null;
                savedMapSlot = -1;
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
        savedMapSlot = mc.player.getInventory().getSelectedSlot();

        // Swap to item frame
        InvUtils.swap(frameSlot, false);

        // Place item frame
        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, blockHitResult);

        if (swing.get()) {
            mc.player.swingHand(Hand.MAIN_HAND);
        }

        // Swap back to map
        InvUtils.swap(savedMapSlot, false);

        // Store frame position and start countdown
        framePos = blockHitResult.getBlockPos().offset(blockHitResult.getSide());
        ticksToWait = mapDelay.get();
    }

    private void tryPlaceMap() {
        if (mc.player == null || mc.world == null || framePos == null) return;

        // Make sure we're holding the map
        ItemStack mainHand = mc.player.getMainHandStack();
        if (!(mainHand.getItem() instanceof FilledMapItem)) {
            if (savedMapSlot != -1) {
                InvUtils.swap(savedMapSlot, false);
            }
            mainHand = mc.player.getMainHandStack();
            if (!(mainHand.getItem() instanceof FilledMapItem)) return;
        }

        // Find the item frame entity
        ItemFrameEntity frame = null;
        Box searchBox = new Box(framePos).expand(0.5);
        
        for (Entity entity : mc.world.getEntities()) {
            if (entity instanceof ItemFrameEntity itemFrame) {
                if (searchBox.intersects(entity.getBoundingBox())) {
                    if (itemFrame.getHeldItemStack().isEmpty()) {
                        frame = itemFrame;
                        break;
                    }
                }
            }
        }

        if (frame != null) {
            // Rotate to the frame and interact
            final ItemFrameEntity targetFrame = frame;
            Vec3d frameCenter = targetFrame.getPos();
            Rotations.rotate(Rotations.getYaw(frameCenter), Rotations.getPitch(frameCenter), () -> {
                mc.interactionManager.interactEntity(mc.player, targetFrame, Hand.MAIN_HAND);
                
                if (swing.get()) {
                    mc.player.swingHand(Hand.MAIN_HAND);
                }
            });
        }
    }
}
