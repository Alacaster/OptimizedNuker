package dev.firstmage.optimizednuker.modules;

final class NukerWorkSet {
    NukerActionQueue crawl = new NukerActionQueue(1);
    NukerActionQueue local = new NukerActionQueue(1);
    NukerActionQueue full = new NukerActionQueue(1);

    final NukerActionQueue.View crawlHeadView = new NukerActionQueue.View();
    final NukerActionQueue.View localHeadView = new NukerActionQueue.View();
    final NukerActionQueue.View fullHeadView = new NukerActionQueue.View();
    final NukerActionQueue.View actionView = new NukerActionQueue.View();

    void ensureQueueCapacities(int workerCapacity, int fullCapacity) {
        int worker = Math.max(1, workerCapacity);
        int fullCap = Math.max(1, fullCapacity);
        if (crawl.capacity() != worker) crawl = new NukerActionQueue(worker);
        if (local.capacity() != worker) local = new NukerActionQueue(worker);
        if (full.capacity() != fullCap) full = new NukerActionQueue(fullCap);
    }


    boolean popNextByPriorityInto(NukerActionQueue.View out) {
        return full.popFirstInto(out) || local.popFirstInto(out) || crawl.popFirstInto(out);
    }

    void clearAll() {
        crawl.clear();
        local.clear();
        full.clear();
    }

    void clearWorkers() {
        crawl.clear();
        local.clear();
    }
}
