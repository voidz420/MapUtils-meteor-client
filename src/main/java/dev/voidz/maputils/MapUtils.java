package dev.voidz.maputils;

import dev.voidz.maputils.modules.FastMap;
import com.mojang.logging.LogUtils;
import meteordevelopment.meteorclient.addons.GithubRepo;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;

public class MapUtils extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();
    public static final Category CATEGORY = new Category("MapUtils");

    @Override
    public void onInitialize() {
        LOG.info("Initializing Mappart Utils");

        // Modules
        Modules.get().add(new FastMap());
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "dev.voidz.maputils";
    }

    @Override
    public GithubRepo getRepo() {
        return new GithubRepo("voidz420", "MapUtils-meteor-client");
    }
}
