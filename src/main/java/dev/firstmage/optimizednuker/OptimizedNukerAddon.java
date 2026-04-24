package dev.firstmage.optimizednuker;

import dev.firstmage.optimizednuker.modules.OptimizedNuker;
import com.mojang.logging.LogUtils;
import meteordevelopment.meteorclient.addons.GithubRepo;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;

public class OptimizedNukerAddon extends MeteorAddon {
    public static final String PACKAGE = "dev.firstmage.optimizednuker";
    public static final String GITHUB_OWNER = "FirstMage";
    public static final String GITHUB_REPOSITORY = "optimized-nuker";

    private static final Logger LOG = LogUtils.getLogger();

    @Override
    public void onInitialize() {
        LOG.info("Initializing Optimized Nuker");
        Modules.get().add(new OptimizedNuker());
    }

    @Override
    public String getPackage() {
        return PACKAGE;
    }

    @Override
    public GithubRepo getRepo() {
        return new GithubRepo(GITHUB_OWNER, GITHUB_REPOSITORY);
    }
}
