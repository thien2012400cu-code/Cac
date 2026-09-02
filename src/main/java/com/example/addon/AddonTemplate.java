package com.example.addon;

import com.example.addon.modules.AutoBuildModule;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AddonTemplate extends MeteorAddon {
    public static final Logger LOG = LoggerFactory.getLogger("AutoBuildAddon");
    public static final Category CATEGORY = new Category("AutoBuild");

    @Override
    public void onInitialize() {
        LOG.info("Initializing AutoBuild addon");

        // Bắt buộc: cảnh báo/ dừng nếu Litematica chưa được cài, addon sẽ vô dụng nếu thiếu
        if (!FabricLoader.getInstance().isModLoaded("litematica")) {
            LOG.warn("Litematica khong duoc phat hien - module AutoBuild se khong hoat dong dung.");
        }

        Modules.get().add(new AutoBuildModule());
    }

    @Override
    public void onRegisterCategories() {
        meteordevelopment.meteorclient.systems.modules.Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "com.example.addon";
    }
}
