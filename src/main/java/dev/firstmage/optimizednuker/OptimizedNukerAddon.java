package dev.firstmage.optimizednuker;

import dev.firstmage.optimizednuker.modules.OptimizedNuker;
import com.mojang.logging.LogUtils;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.addons.GithubRepo;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;
import org.slf4j.Logger;

public class OptimizedNukerAddon extends MeteorAddon {
    public static final String PACKAGE = "dev.firstmage.optimizednuker";
    public static final String GITHUB_OWNER = "FirstMage";
    public static final String GITHUB_REPOSITORY = "optimized-nuker";

    private static final Logger LOG = LogUtils.getLogger();

    /**
     * The module instance, retained so the always-on profiler HUD subscriber can
     * drive {@link OptimizedNuker#renderProfilerHud} regardless of module activity.
     * The module's own event handlers don't fire while it is deactivated, so the
     * HUD render path lives here at the addon level instead.
     */
    private OptimizedNuker module;

    @Override
    public void onInitialize() {
        LOG.info("Initializing Optimized Nuker");
        module = new OptimizedNuker();
        Modules.get().add(module);
        // Subscribe THIS addon to the global event bus so the profiler HUD keeps
        // rendering its last-captured state after the user disables the module.
        MeteorClient.EVENT_BUS.subscribe(this);
    }

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        if (module != null) module.renderProfilerHud(event);
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
