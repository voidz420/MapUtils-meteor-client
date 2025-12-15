package dev.voidz.maputils.modules;

import dev.voidz.maputils.MapUtils;
import meteordevelopment.meteorclient.events.entity.EntityAddedEvent;
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

    private final Setting<Integer> rotateTicks = sgGeneral.add(new IntSetting.Builder()
        .name("rotate-ticks")
        .description("How many ticks to rotate toward frame before placing map.")
        .defaultValue(3)
        .min(1)
        .sliderRange(1, 10)
        .build()
    );

    private final Setting<Integer> interactRetries = sgGeneral.add(new IntSetting.Builder()
        .name("interact-retries")
        .description("How many times to send interact packet (0-10).")
        .defaultValue(2)
        .min(0)
        .max(10)
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

    private long lastPlaceTime = 0;
    private boolean wasPressed = false;
    
    // Track placed frame to put map on it
    private BlockPos waitingForFrameAt = null;
    private int mapSlot = -1;
    
    // Rotation state
    private ItemFrameEntity targetFrame = null;
    private int ticksRotating = 0;

    public FastMap() {
        super(MapUtils.CATEGORY, "fast-map", "Hold a map and click to place item frame + map. Rotates to frame before placing map (for 2b2t).");
    }

    @Override
    public void onActivate() {
        lastPlaceTime = 0;
        wasPressed = false;
        waitingForFrameAt = null;
        mapSlot = -1;
        targetFrame = null;
        ticksRotating = 0;
    }

    @Override
    public void onDeactivate() {
        waitingForFrameAt = null;
        mapSlot = -1;
        targetFrame = null;
        ticksRotating = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        // Handle rotation and map placement
        if (targetFrame != null) {
            // Keep rotating to the frame
            Vec3d framePos = targetFrame.getPos();
            Rotations.rotate(Rotations.getYaw(framePos), Rotations.getPitch(framePos));
            
            ticksRotating++;
            
            // After enough ticks, place the map
            if (ticksRotating >= rotateTicks.get()) {
                placeMapOnFrame();
            }
            return;
        }

        // Normal click detection
        ItemStack mainHand = mc.player.getMainHandStack();
        if (!(mainHand.getItem() instanceof FilledMapItem)) {
            wasPressed = false;
            return;
        }

        FindItemResult frameResult = InvUtils.findInHotbar(Items.ITEM_FRAME, Items.GLOW_ITEM_FRAME);
        if (!frameResult.found()) {
            wasPressed = false;
            return;
        }

        HitResult hitResult = mc.getCameraEntity().raycast(mc.player.getBlockInteractionRange(), 0, false);
        if (!(hitResult instanceof BlockHitResult blockHitResult)) {
            wasPressed = false;
            return;
        }

        if (onlyWalls.get()) {
            Direction side = blockHitResult.getSide();
            if (side == Direction.UP || side == Direction.DOWN) {
                wasPressed = false;
                return;
            }
        }

        boolean isPressed = mc.options.useKey.isPressed();

        if (mc.currentScreen != null) {
            wasPressed = isPressed;
            return;
        }

        long currentTime = System.currentTimeMillis();
        if (currentTime - lastPlaceTime < placeDelay.get()) {
            wasPressed = isPressed;
            return;
        }

        if (isPressed && !wasPressed) {
            placeFrame(blockHitResult, frameResult.slot());
            lastPlaceTime = currentTime;
        }

        wasPressed = isPressed;
    }

    private void placeFrame(BlockHitResult blockHitResult, int frameSlot) {
        mapSlot = mc.player.getInventory().getSelectedSlot();
        waitingForFrameAt = blockHitResult.getBlockPos().offset(blockHitResult.getSide());

        // Swap to item frame
        InvUtils.swap(frameSlot, false);

        // Place item frame
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
        if (targetFrame != null) return; // Already processing one

        Entity entity = event.entity;
        
        if (!(entity instanceof ItemFrameEntity frame)) return;

        double dist = entity.getPos().squaredDistanceTo(Vec3d.ofCenter(waitingForFrameAt));
        if (dist > 2.0) return;

        if (!frame.getHeldItemStack().isEmpty()) return;

        // Make sure we're holding a map
        ItemStack mainHand = mc.player.getMainHandStack();
        if (!(mainHand.getItem() instanceof FilledMapItem)) {
            if (mapSlot != -1) {
                InvUtils.swap(mapSlot, false);
            }
        }

        // Start rotating to the frame
        targetFrame = frame;
        ticksRotating = 0;
        waitingForFrameAt = null;
        
        // Start rotation immediately
        Vec3d framePos = frame.getPos();
        Rotations.rotate(Rotations.getYaw(framePos), Rotations.getPitch(framePos));
    }

    private void placeMapOnFrame() {
        if (mc.player == null || targetFrame == null) {
            resetState();
            return;
        }

        // Make sure we're holding a map
        ItemStack mainHand = mc.player.getMainHandStack();
        if (!(mainHand.getItem() instanceof FilledMapItem)) {
            if (mapSlot != -1) {
                InvUtils.swap(mapSlot, false);
                mainHand = mc.player.getMainHandStack();
            }
        }

        if (mainHand.getItem() instanceof FilledMapItem) {
            // Interact with the frame multiple times based on setting
            int retries = interactRetries.get();
            for (int i = 0; i < retries; i++) {
                mc.interactionManager.interactEntity(mc.player, targetFrame, Hand.MAIN_HAND);
            }

            if (swing.get()) {
                mc.player.swingHand(Hand.MAIN_HAND);
            }
        }

        resetState();
    }

    private void resetState() {
        targetFrame = null;
        ticksRotating = 0;
        mapSlot = -1;
    }
}
