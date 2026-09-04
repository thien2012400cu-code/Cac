package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.BlockState;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.state.property.Property;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;

public class AutoBuildModule extends Module {

    // Nhom thieu so dung quy tac NGUOC chieu nhin (mat truoc quay ve nguoi choi).
    // Da so block co facing con lai (stairs, thang leo, nut, bien hieu...) dung CUNG chieu nhin.
    private static final Set<Block> OPPOSITE_DIRECTION_FACING_BLOCKS = Set.of(
        Blocks.FURNACE, Blocks.BLAST_FURNACE, Blocks.SMOKER
    );

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
    private final Deque<FixupTask> fixupQueue = new ArrayDeque<>();
    private int rescanTimer = 0;

    public AutoBuildModule() {
        super(AddonTemplate.CATEGORY, "auto-build", "Tu dong dat block theo schematic Litematica dang active.");
    }

    @Override
    public void onActivate() {
        queue.clear();
        fixupQueue.clear();
        rescanTimer = 0;
        AddonTemplate.LOG.info("[AutoBuild] Module bat len.");
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null) return;

        if (!fixupQueue.isEmpty()) {
            FixupTask task = fixupQueue.peekFirst();
            fixupQueue.pollFirst();
            processFixup(task);
            return;
        }

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

    private boolean selectMatchingHotbarSlot(Block desiredBlock) {
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = mc.player.getInventory().getStack(slot);
            if (stack.isEmpty()) continue;
            if (stack.getItem() instanceof BlockItem blockItem && blockItem.getBlock() == desiredBlock) {
                mc.player.getInventory().setSelectedSlot(slot);
                return true;
            }
        }
        return false;
    }

    private boolean tryPlace(PendingPlacement p) {
        if (!mc.player.getBlockPos().isWithinDistance(p.pos(), reach.get())) {
            return false;
        }

        if (!selectMatchingHotbarSlot(p.state().getBlock())) {
            return false;
        }

        Direction desiredFacing = getDesiredFacing(p.state());
        BlockPos neighborPos;
        Direction clickSide;
        Direction aimDir = null;

        if (desiredFacing != null) {
            boolean opposite = OPPOSITE_DIRECTION_FACING_BLOCKS.contains(p.state().getBlock());
            aimDir = opposite ? desiredFacing.getOpposite() : desiredFacing;

            // QUAN TRONG: khoi ke PHAI nam dung doc theo huong se bi ep nhin, khong
            // duoc chon bua mat nao khac - neu khong, server tu raycast se thay
            // huong nhin va mat click khong khop nhau, tu bac/tinh lai facing sai.
            neighborPos = p.pos().offset(aimDir.getOpposite());
            clickSide = aimDir;

            if (mc.world.getBlockState(neighborPos).isAir()) {
                // Chua co khoi ke phu hop de dam bao huong nhin khop mat click.
                // Bo qua vi tri nay o vong nay, doi vong quet sau khi da co du khoi xung quanh.
                return false;
            }
        } else {
            Direction supportFace = findSupportFace(p.pos());
            if (supportFace != null) {
                neighborPos = p.pos().offset(supportFace);
                clickSide = supportFace.getOpposite();
            } else {
                if (requireExistingSupport.get()) return false;
                neighborPos = p.pos().offset(Direction.DOWN);
                clickSide = Direction.UP;
            }
        }

        Vec3d hitVec = Vec3d.ofCenter(neighborPos).add(
            clickSide.getOffsetX() * 0.5,
            clickSide.getOffsetY() * 0.5,
            clickSide.getOffsetZ() * 0.5
        );

        double dx, dy, dz;
        if (aimDir != null) {
            dx = aimDir.getOffsetX();
            dy = aimDir.getOffsetY();
            dz = aimDir.getOffsetZ();
        } else {
            dx = hitVec.x - mc.player.getX();
            dy = hitVec.y - (mc.player.getY() + mc.player.getEyeHeight(mc.player.getPose()));
            dz = hitVec.z - mc.player.getZ();
        }

        double distXZ = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, distXZ));

        BlockPos neighborPosFinal = neighborPos;
        Direction clickSideFinal = clickSide;
        Vec3d hitVecFinal = hitVec;
        BlockPos logPos = p.pos();
        BlockState logDesired = p.state();
        float yawFinal = yaw;
        float pitchFinal = pitch;

        Rotations.rotate(yaw, pitch, 100, true, () -> {
            if (mc.world.getBlockState(neighborPosFinal).isAir()) {
                return;
            }

            BlockHitResult hitResult = new BlockHitResult(hitVecFinal, clickSideFinal, neighborPosFinal, false);
            mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hitResult);
            mc.player.swingHand(Hand.MAIN_HAND);

            if (logDesired.getBlock() == Blocks.OBSERVER) {
                BlockState realAfter = mc.world.getBlockState(logPos);
                AddonTemplate.LOG.info("[AutoBuild] OBSERVER tai " + logPos
                    + " | mong muon: " + logDesired
                    + " | thuc te: " + realAfter
                    + " | yaw/pitch da xoay: " + yawFinal + "/" + pitchFinal);
            }

            fixupQueue.addLast(new FixupTask(logPos, logDesired, 0));
        });

        return true;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Comparable<?> getPropValue(BlockState state, Property<?> prop) {
        return state.get((Property) prop);
    }

    private Direction getDesiredFacing(BlockState desired) {
        for (Property<?> prop : desired.getProperties()) {
            if (prop instanceof EnumProperty<?> enumProp && prop.getName().equals("facing")) {
                Comparable<?> value = getPropValue(desired, enumProp);
                if (value instanceof Direction dir) {
                    return dir;
                }
            }
        }
        return null;
    }

    private Direction findSupportFace(BlockPos pos) {
        for (Direction dir : Direction.values()) {
            BlockPos neighbor = pos.offset(dir);
            BlockState state = mc.world.getBlockState(neighbor);
            if (!state.isAir()) return dir;
        }
        return null;
    }

    private boolean processFixup(FixupTask task) {
        if (mc.world == null || mc.player == null) return true;
        if (task.attempts() >= 4) return true;

        BlockState real = mc.world.getBlockState(task.pos());
        BlockState desired = task.desired();

        if (real.getBlock() != desired.getBlock()) return true;

        boolean needsClick = false;
        for (Property<?> prop : desired.getProperties()) {
            String name = prop.getName();
            if (name.equals("open") || name.equals("waterlogged") || name.equals("facing")
                || name.equals("axis") || name.equals("half") || name.equals("shape")) {
                continue;
            }
            Comparable<?> desiredVal = getPropValue(desired, prop);
            Comparable<?> realVal = getPropValue(real, prop);
            if (!desiredVal.equals(realVal)) {
                needsClick = true;
                break;
            }
        }

        if (!needsClick) return true;

        if (!mc.player.getBlockPos().isWithinDistance(task.pos(), reach.get())) {
            return true;
        }

        Vec3d hitVec = Vec3d.ofCenter(task.pos());
        BlockHitResult hitResult = new BlockHitResult(hitVec, Direction.UP, task.pos(), false);
        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hitResult);
        mc.player.swingHand(Hand.MAIN_HAND);

        fixupQueue.addLast(new FixupTask(task.pos(), task.desired(), task.attempts() + 1));
        return true;
    }

    private record PendingPlacement(BlockPos pos, BlockState state) {}
    private record FixupTask(BlockPos pos, BlockState desired, int attempts) {}
}
