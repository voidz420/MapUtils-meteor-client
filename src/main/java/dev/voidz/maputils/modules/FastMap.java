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
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
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
        .description("Ticks to wait before placing map after frame (increase on high ping).")
        .defaultValue(5)
        .min(1)
        .sliderRange(1, 20)
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

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Rotate to the item frame when placing map.")
        .defaultValue(true)
        .build()
    );

    private long lastPlaceTime = 0;
    private boolean wasPressed = false;
    
    // For delayed map placement
    private int ticksToWait = 0;
    private BlockPos framePos = null;
    private int savedMapSlot = -1;
    private int retryCount = 0;
    private static final int MAX_RETRIES = 10;
    
    // For rotation then interact
    private ItemFrameEntity pendingFrame = null;
    private int rotationTicks = 0;

    public FastMap() {
        super(MapUtils.CATEGORY, "fast-map", "Hold a map and click to place item frame + map in one action.");
    }

    @Override
    public void onActivate() {
        lastPlaceTime = 0;
        wasPressed = false;
        resetState();
    }

    @Override
    public void onDeactivate() {
        resetState();
    }

    private void resetState() {
        ticksToWait = 0;
        framePos = null;
        savedMapSlot = -1;
        retryCount = 0;
        pendingFrame = null;
        rotationTicks = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;

        // Handle pending interaction after rotation
        if (pendingFrame != null) {
            rotationTicks++;
            
            // Keep rotating
            Vec3d frameCenter = pendingFrame.getPos();
            if (rotate.get()) {
                Rotations.rotate(Rotations.getYaw(frameCenter), Rotations.getPitch(frameCenter));
            }
            
            // After 2 ticks of rotation, interact
            if (rotationTicks >= 2) {
                mc.interactionManager.interactEntity(mc.player, pendingFrame, Hand.MAIN_HAND);
                
                if (swing.get()) {
                    mc.player.swingHand(Hand.MAIN_HAND);
                }
                
                pendingFrame = null;
                rotationTicks = 0;
            }
            return;
        }

        // Handle pending map placement - keep trying until we find the frame
        if (framePos != null) {
            if (ticksToWait > 0) {
                ticksToWait--;
                return;
            }
            
            // Try to find frame and start rotation
            boolean success = tryFindFrameAndRotate();
            if (success || retryCount >= MAX_RETRIES) {
                framePos = null;
                savedMapSlot = -1;
                retryCount = 0;
            } else {
                retryCount++;
                ticksToWait = 1; // Try again next tick
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

        // Place item frame using packet
        mc.getNetworkHandler().sendPacket(new PlayerInteractBlockC2SPacket(
            Hand.MAIN_HAND,
            blockHitResult,
            0
        ));

        if (swing.get()) {
            mc.getNetworkHandler().sendPacket(new HandSwingC2SPacket(Hand.MAIN_HAND));
        }

        // Swap back to map
        InvUtils.swap(savedMapSlot, false);

        // Store frame position and start countdown
        framePos = blockHitResult.getBlockPos().offset(blockHitResult.getSide());
        ticksToWait = mapDelay.get();
        retryCount = 0;
    }

    private boolean tryFindFrameAndRotate() {
        if (mc.player == null || mc.world == null || framePos == null) return false;

        // Make sure we're holding the map
        ItemStack mainHand = mc.player.getMainHandStack();
        if (!(mainHand.getItem() instanceof FilledMapItem)) {
            if (savedMapSlot != -1) {
                InvUtils.swap(savedMapSlot, false);
            }
            mainHand = mc.player.getMainHandStack();
            if (!(mainHand.getItem() instanceof FilledMapItem)) return false;
        }

        // Find the item frame entity at the expected position
        ItemFrameEntity frame = findEmptyFrameAt(framePos);
        
        if (frame == null) {
            return false; // Frame not spawned yet, will retry
        }

        // Start rotation, will interact after a couple ticks
        pendingFrame = frame;
        rotationTicks = 0;
        
        // Start rotating immediately
        Vec3d frameCenter = frame.getPos();
        if (rotate.get()) {
            Rotations.rotate(Rotations.getYaw(frameCenter), Rotations.getPitch(frameCenter));
        }

        return true;
    }

    private ItemFrameEntity findEmptyFrameAt(BlockPos pos) {
        Box searchBox = new Box(pos).expand(0.6);
        
        for (Entity entity : mc.world.getEntities()) {
            if (entity instanceof ItemFrameEntity itemFrame) {
                if (itemFrame.getBoundingBox().intersects(searchBox)) {
                    if (itemFrame.getHeldItemStack().isEmpty()) {
                        return itemFrame;
                    }
                }
            }
        }
        return null;
    }
}
