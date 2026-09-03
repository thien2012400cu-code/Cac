package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.state.property.Property;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.StringIdentifiable;
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

    // Danh sach vi tri vua dat, can kiem tra lai/chinh trang thai (click-toggle)
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

        // Uu tien xu ly fixup (chinh trang thai click-toggle) truoc
        if (!fixupQueue.isEmpty()) {
            FixupTask task = fixupQueue.peekFirst();
            if (processFixup(task)) {
                fixupQueue.pollFirst();
            } else {
                fixupQueue.pollFirst();
            }
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
                fixupQueue.addLast(new FixupTask(p.pos(), p.state(), 0));
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

        Direction supportFace = findBestSupportFace(p);
        BlockPos neighborPos;
        Direction clickSide;

        if (supportFace != null) {
            neighborPos = p.pos().offset(supportFace);
            clickSide = supportFace.getOpposite();
        } else {
            if (requireExistingSupport.get()) return false;
            neighborPos = p.pos().offset(Direction.DOWN);
            clickSide = Direction.UP;
        }

        Vec3d hitVec = Vec3d.ofCenter(neighborPos).add(
            clickSide.getOffsetX() * 0.5,
            clickSide.getOffsetY() * 0.5,
            clickSide.getOffsetZ() * 0.5
        );

        // Tinh goc nhin: neu block co huong ngang (facing) mong muon, uu tien nhin theo
        // huong DOI DIEN voi facing do (giong nguoi choi that dung quay lung lai huong facing).
        Direction desiredHorizontalFacing = getDesiredHorizontalFacing(p.state());
        double dx, dz;
        if (desiredHorizontalFacing != null) {
            dx = -desiredHorizontalFacing.getOffsetX();
            dz = -desiredHorizontalFacing.getOffsetZ();
        } else {
            dx = hitVec.x - mc.player.getX();
            dz = hitVec.z - mc.player.getZ();
        }
        double dy = hitVec.y - (mc.player.getY() + mc.player.getEyeHeight(mc.player.getPose()));
        double dist = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, dist));

        Rotations.rotate(yaw, pitch, 100, false, () -> {
            BlockHitResult hitResult = new BlockHitResult(hitVec, clickSide, neighborPos, false);
            mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hitResult);
            mc.player.swingHand(Hand.MAIN_HAND);
        });

        return true;
    }

    /**
     * Neu block co thuoc tinh facing ngang (HORIZONTAL_FACING kieu Direction),
     * lay gia tri mong muon tu desired state. Tra ve null neu khong co.
     */
    @SuppressWarnings("unchecked")
    private Direction getDesiredHorizontalFacing(BlockState desired) {
        for (Property<?> prop : desired.getProperties()) {
            if (prop instanceof EnumProperty<?> enumProp && prop.getName().equals("facing")) {
                Comparable<?> value = desired.get((Property<Comparable<?>>) (Property<?>) enumProp);
                if (value instanceof Direction dir && dir.getAxis().isHorizontal()) {
                    return dir;
                }
            }
        }
        return null;
    }

    /**
     * Tim mat ke tot nhat de "click" vao khi dat block. Uu tien mat tuong ung voi
     * huong nguoc lai facing mong muon (de placement logic cua Minecraft tu suy
     * ra dung facing), neu khong co thi lay bat ky mat nao da co block that.
     */
    private Direction findBestSupportFace(PendingPlacement p) {
        Direction desiredFacing = getDesiredHorizontalFacing(p.state());
        if (desiredFacing != null) {
            Direction preferred = desiredFacing.getOpposite();
            BlockPos preferredNeighbor = p.pos().offset(preferred);
            if (!mc.world.getBlockState(preferredNeighbor).isAir()) {
                return preferred;
            }
        }
        return findSupportFace(p.pos());
    }

    private Direction findSupportFace(BlockPos pos) {
        for (Direction dir : Direction.values()) {
            BlockPos neighbor = pos.offset(dir);
            BlockState state = mc.world.getBlockState(neighbor);
            if (!state.isAir()) return dir;
        }
        return null;
    }

    /**
     * Sau khi dat, so sanh state that voi state mong muon. Neu con thuoc tinh
     * kieu "click de doi" (vi du: comparator mode, lever/button powered...)
     * chua khop, right-click vao block do de thu doi trang thai, gioi han so lan
     * thu de tranh vong lap vo han.
     */
    @SuppressWarnings("unchecked")
    private boolean processFixup(FixupTask task) {
        if (mc.world == null || mc.player == null) return true;
        if (task.attempts() >= 4) return true; // qua so lan cho phep, bo qua

        BlockState real = mc.world.getBlockState(task.pos());
        BlockState desired = task.desired();

        if (real.getBlock() != desired.getBlock()) return true; // block bi thay doi/pha, bo qua

        // Bo qua thuoc tinh khong the/khong nen ep bang click:
        // - "open" (cua/trapdoor): de redstone tu xu ly, bo qua theo yeu cau
        // - "waterlogged": khong doi bang click
        boolean needsClick = false;
        for (Property<?> prop : desired.getProperties()) {
            String name = prop.getName();
            if (name.equals("open") || name.equals("waterlogged") || name.equals("facing")
                || name.equals("axis") || name.equals("half") || name.equals("shape")) {
                continue;
            }
            Comparable<?> desiredVal = desired.get((Property<Comparable<?>>) (Property<?>) prop);
            Comparable<?> realVal = real.get((Property<Comparable<?>>) (Property<?>) prop);
            if (!desiredVal.equals(realVal)) {
                needsClick = true;
                break;
            }
        }

        if (!needsClick) return true; // da khop, xong

        if (!mc.player.getBlockPos().isWithinDistance(task.pos(), reach.get())) {
            return true; // qua xa, bo qua fixup nay
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
