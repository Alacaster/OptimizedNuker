package dev.firstmage.optimizednuker.modules;

/**
 * Owns the two crawl queues and the reusable views used by produce/consume sites.
 * Low-side work is consumed before high-side fallback work.
 */
final class NukerWorkSet {
    NukerActionQueue crawlHigh = new NukerActionQueue(1);
    NukerActionQueue crawlLow = new NukerActionQueue(1);

    final NukerActionQueue.View crawlHeadView = new NukerActionQueue.View();
    final NukerActionQueue.View actionView = new NukerActionQueue.View();

    void ensureQueueCapacities(int actionCapacity) {
        int perQueueCapacity = Math.max(1, actionCapacity);
        if (crawlHigh.capacity() != perQueueCapacity) crawlHigh = new NukerActionQueue(perQueueCapacity);
        if (crawlLow.capacity() != perQueueCapacity) crawlLow = new NukerActionQueue(perQueueCapacity);
    }

    boolean popNextCrawlActionInto(NukerActionQueue.View out) {
        if (crawlLow.popLastInto(out)) return true;
        return crawlHigh.popFirstInto(out);
    }

    boolean peekNextCrawlActionInto(NukerActionQueue.View out) {
        if (crawlLow.peekLastInto(out)) return true;
        return crawlHigh.peekFirstInto(out);
    }

    int queuedActionCount() {
        return crawlHigh.size() + crawlLow.size();
    }

    boolean hasQueuedActions() {
        return !crawlHigh.isEmpty() || !crawlLow.isEmpty();
    }

    boolean queuesFull() {
        return crawlHigh.isFull() && crawlLow.isFull();
    }

    void clearQueues() {
        crawlHigh.clear();
        crawlLow.clear();
    }
}
