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

// --- Import Litematica ---
// LƯU Ý PACKAGE: các bản Litematica gần đây (build chính chủ maruohon cho MC mới)
// đã đổi package từ "fi.dy.masa.litematica.*" sang "litematica.*".
// Một số fork phổ biến (vd sakura-ryoko) VẪN dùng "fi.dy.masa.litematica.*".
// -> Mở jar Litematica bạn đang cài (hoặc gõ "litematica." trong IDE và để
// auto-import gợi ý) để biết chính xác package nào áp dụng cho bản của bạn,
// rồi sửa 2 dòng import bên dưới cho khớp.
import litematica.data.DataManager;
import litematica.world.SchematicWorldHandler;
import litematica.world.WorldSchematic;
// Nếu IDE báo không tìm thấy 2 class trên, thử thay bằng:
// import fi.dy.masa.litematica.data.DataManager;
// import fi.dy.masa.litematica.world.SchematicWorldHandler;
// import fi.dy.masa.litematica.world.WorldSchematic;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Module tự động đặt block khớp với schematic đang active trong Litematica.
 *
 * QUAN TRỌNG: phần đọc dữ liệu từ Litematica (getMissingPositions) là SKELETON.
 * Litematica dùng internal API đổi theo version -> bạn cần mở source Litematica
 * đúng version của mình (github.com/maruohon/litematica) để lấy đúng class/method,
 * rồi thay vào TODO bên dưới.
 */
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

    // Hàng đợi các vị trí cần đặt, tính lại định kỳ thay vì mỗi tick (đỡ tốn CPU)
    private final Deque<PendingPlacement> queue = new ArrayDeque<>();
    private int rescanTimer = 0;

    public AutoBuildModule() {
        super(AddonTemplate.CATEGORY, "auto-build", "Tu dong dat block theo schematic Litematica dang active.");
    }

    @Override
    public void onActivate() {
        queue.clear();
        rescanTimer = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null) return;

        // Định kỳ quét lại danh sách vị trí còn thiếu (mỗi 20 tick = 1 giây)
        if (rescanTimer <= 0) {
            queue.clear();
            queue.addAll(getMissingPositions());
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
                // Không đặt được (quá xa, không có mặt kề...) -> bỏ qua vòng này, thử lại lần quét sau
                queue.pollFirst();
            }
        }
    }

    /**
     * Quét quanh người chơi, so sánh block trong "world ảo" của Litematica
     * (world chỉ dùng để render ghost-block, chứa toàn bộ schematic đang active)
     * với world thật, trả về danh sách vị trí còn thiếu.
     *
     * Dựa trên cơ chế thật của Litematica: hệ thống render của nó cũng làm
     * đúng việc so sánh schematicWorldView.getBlockState() vs clientWorldView.getBlockState()
     * để quyết định vẽ ghost-block ở đâu — ở đây mình tái dùng world đó thay vì
     * tự parse container NBT thô, đỡ phải lo version-specific data structure.
     */
    private java.util.List<PendingPlacement> getMissingPositions() {
        java.util.List<PendingPlacement> result = new java.util.ArrayList<>();
        if (mc.player == null || mc.world == null) return result;

        WorldSchematic schematicWorld = SchematicWorldHandler.getInstance().getSchematicWorld();
        // Nếu API bản bạn không có getInstance()/getSchematicWorld() y hệt vậy,
        // thử: DataManager.getSchematicPlacementManager() rồi tìm phương thức
        // tương đương trả về world/handler chứa dữ liệu ghost-block.
        if (schematicWorld == null) return result;

        BlockPos playerPos = mc.player.getBlockPos();
        int r = scanRadius.get();

        BlockPos.Mutable mutable = new BlockPos.Mutable();
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    mutable.set(playerPos.getX() + dx, playerPos.getY() + dy, playerPos.getZ() + dz);

                    BlockState desired = schematicWorld.getBlockState(mutable);
                    if (desired.isAir()) continue; // schematic không yêu cầu block ở đây

                    BlockState real = mc.world.getBlockState(mutable);
                    if (real.isAir() && !desired.equals(real)) {
                        result.add(new PendingPlacement(mutable.toImmutable(), desired));
                    }
                    // Không xử lý trường hợp block sai loại đang chiếm chỗ (cần phá trước) -
                    // module này chỉ ĐẶT, không PHÁ, để tránh nguy hiểm (phá nhầm base...).
                }
            }
        }

        // Sắp theo khoảng cách gần người chơi trước, để đặt hợp lý hơn
        result.sort(java.util.Comparator.comparingDouble(p ->
            p.pos().getSquaredDistance(mc.player.getPos())));

        return result;
    }

    /**
     * Thử đặt 1 block: tìm mặt kề (Direction) có block thật đã tồn tại để "click" vào,
     * giống hành vi người chơi thật, rồi gọi interactionManager để gửi packet place.
     */
    private boolean tryPlace(PendingPlacement p) {
        if (mc.player.getPos().distanceTo(Vec3d.ofCenter(p.pos)) > reach.get()) return false;

        Direction supportFace = findSupportFace(p.pos);
        if (supportFace == null) {
            if (requireExistingSupport.get()) return false;
            supportFace = Direction.UP; // fallback đơn giản nếu không cần support
        }

        BlockPos neighborPos = p.pos.offset(supportFace.getOpposite());
        Vec3d hitVec = Vec3d.ofCenter(neighborPos).add(
            supportFace.getOffsetX() * 0.5,
            supportFace.getOffsetY() * 0.5,
            supportFace.getOffsetZ() * 0.5
        );

        BlockHitResult hitResult = new BlockHitResult(hitVec, supportFace, neighborPos, false);

        // TODO: đảm bảo đúng item đang cầm trên hotbar khớp với p.state trước khi place
        // (tìm slot chứa item tương ứng, switch hotbar, hoặc dùng inventory swap nếu creative).

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
