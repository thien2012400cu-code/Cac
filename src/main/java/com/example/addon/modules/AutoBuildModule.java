package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Deque;

public class AutoBuildModule extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> blocksPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("blocks-per-tick")
        .description("So block toi da dat moi tick, de tranh spam packet / bi anti-cheat flag.")
        .defaultValue(1)
        .min(1)
        .sliderMax(10)
        .build()
    );

    private final Setting<Double> reach = sgGeneral.add(new DoubleSetting.Builder()
        .name("reach")
        .description("Khoang cach toi da de dat block.")
        .defaultValue(4.5)
        .min(1)
        .sliderMax(6)
        .build()
    );

    private final Setting<Boolean> requireExistingSupport = sgGeneral.add(new BoolSetting.Builder()
        .name("require-support")
        .description("Chi dat block neu co it nhat 1 mat ke da co block that (giong nguoi choi that).")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> scanRadius = sgGeneral.add(new IntSetting.Builder()
        .name("scan-radius")
        .description("Ban kinh (block) quanh nguoi choi de quet vi tri con thieu. Cang lon cang lag.")
        .defaultValue(8)
        .min(1)
        .sliderMax(16)
        .build()
    );

    private final Deque<PendingPlacement> queue = new ArrayDeque<>();
    private int rescanTimer = 0;

    public AutoBuildModule() {
        super(AddonTemplate.CATEGORY, "auto-build", "Tu dong dat block theo schematic Litematica dang active.");
    }

    @Override
    public void onActivate() {
        queue.clear();
        rescanTimer = 0;
        AddonTemplate.LOG.info("[AutoBuild] Module bat len.");
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null) return;

        if (rescanTimer <= 0) {
            queue.clear();
            queue.addAll(getMissingPositions());
            if (!queue.isEmpty()) {
                AddonTemplate.LOG.info("[AutoBuild] Tim thay " + queue.size() + " vi tri can dat.");
            }
            rescanTimer = 20;
        } else {
            rescanTimer--;
        }

        int placed = 0;
        while (placed < blocksPerTick.get() && !queue.isEmpty()) {
            PendingPlacement p = queue.peekFirst();
            if (tryPlace(p)) {
                queue.pollFirst();
                placed++;
            } else {
                queue.pollFirst();
            }
        }
    }

    private java.util.List<PendingPlacement> getMissingPositions() {
        java.util.List<PendingPlacement> result = new java.util.ArrayList<>();
        if (mc.player == null || mc.world == null) return result;

        Object schematicWorld = getSchematicWorldViaReflection();
        if (schematicWorld == null) return result;

        try {
            if (!(schematicWorld instanceof World)) {
                AddonTemplate.LOG.warn("[AutoBuild] schematicWorld khong phai instance cua World: " + schematicWorld.getClass().getName());
                return result;
            }
            World schematicMcWorld = (World) schematicWorld;

            BlockPos playerPos = mc.player.getBlockPos();
            int r = scanRadius.get();

            BlockPos.Mutable mutable = new BlockPos.Mutable();
            for (int dx = -r; dx <= r; dx++) {
                for (int dy = -r; dy <= r; dy++) {
                    for (int dz = -r; dz <= r; dz++) {
                        mutable.set(playerPos.getX() + dx, playerPos.getY() + dy, playerPos.getZ() + dz);

                        BlockState desired = schematicMcWorld.getBlockState(mutable);
                        if (desired == null || desired.isAir()) continue;

                        BlockState real = mc.world.getBlockState(mutable);
                        if (real.isAir()) {
                            result.add(new PendingPlacement(mutable.toImmutable(), desired));
                        }
                    }
                }
            }
        } catch (Exception e) {
            AddonTemplate.LOG.error("[AutoBuild] Loi khi doc du lieu Litematica", e);
        }

        result.sort(java.util.Comparator.comparingDouble(p ->
            p.pos().getSquaredDistance(mc.player.getBlockPos())));

        return result;
    }

    private Object getSchematicWorldViaReflection() {
        String[] candidateClasses = {
            "litematica.world.SchematicWorldHandler",
            "fi.dy.masa.litematica.world.SchematicWorldHandler"
        };

        for (String className : candidateClasses) {
            try {
                Class<?> handlerClass = Class.forName(className);

                Object world = null;

                try {
                    Method staticGetWorld = handlerClass.getMethod("getSchematicWorld");
                    world = staticGetWorld.invoke(null);
                } catch (NoSuchMethodException noStatic) {
                    try {
                        Method getInstance = handlerClass.getMethod("getInstance");
                        Object handler = getInstance.invoke(null);
                        Method getWorld = handlerClass.getMethod("getSchematicWorld");
                        world = getWorld.invoke(handler);
                    } catch (Exception fallbackEx) {
                        AddonTemplate.LOG.warn("[AutoBuild] Ca 2 cach goi deu that bai: " + fallbackEx);
                    }
                }

                if (world != null) {
                    return world;
                }
            } catch (ClassNotFoundException ignored) {
            } catch (Exception e) {
                AddonTemplate.LOG.warn("[AutoBuild] Tim thay class " + className + " nhung goi loi: " + e);
            }
        }

        return null;
    }

    private boolean tryPlace(PendingPlacement p) {
        if (!mc.player.getBlockPos().isWithinDistance(p.pos(), reach.get())) {
            return false;
        }

        Direction supportFace = findSupportFace(p.pos());
        if (supportFace == null) {
            if (requireExistingSupport.get()) return false;
            supportFace = Direction.UP;
        }

        BlockPos neighborPos = p.pos().offset(supportFace.getOpposite());
        Vec3d hitVec = Vec3d.ofCenter(neighborPos).add(
            supportFace.getOffsetX() * 0.5,
            supportFace.getOffsetY() * 0.5,
            supportFace.getOffsetZ() * 0.5
        );

        BlockHitResult hitResult = new BlockHitResult(hitVec, supportFace, neighborPos, false);

        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hitResult);
        mc.player.swingHand(Hand.MAIN_HAND);

        return true;
    }

    private Direction findSupportFace(BlockPos pos) {
        for (Direction dir : Direction.values()) {
            BlockPos neighbor = pos.offset(dir);
            BlockState state = mc.world.getBlockState(neighbor);
            if (!state.isAir()) return dir;
        }
        return null;
    }

    private record PendingPlacement(BlockPos pos, BlockState state) {}
}
