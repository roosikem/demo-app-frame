package com.demo.ejb.faceframe;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * In-memory store of the FaceFrame stencil files, filled once at application start.
 */
@ApplicationScoped
public class StencilCache {

    public static final String MANIFEST = StencilManifest.FILE_NAME;

    private static final Logger LOG = Logger.getLogger(StencilCache.class.getName());

    private final StencilZipService zipService;

    private final AtomicBoolean started = new AtomicBoolean();
    private volatile Map<String, StencilFile> files = Map.of();
    private volatile boolean loaded;

    @Inject
    public StencilCache(StencilZipService zipService) {
        this.zipService = zipService;
    }

    /** Required by CDI for the client proxy of an application scoped bean; not for direct use. */
    protected StencilCache() {
        this(null);
    }

    void onStart(@Observes @Initialized(ApplicationScoped.class) Object event) {
        // an EAR can deliver the event once per module; the zip is loaded only once
        if (!started.compareAndSet(false, true)) {
            return;
        }
        try {
            store(zipService.load());
        } catch (StencilException | RuntimeException e) {
            LOG.log(Level.SEVERE, e, () -> "Stencils not loaded; only the built-in stencils are available");
        }
    }

    /** Replaces all files at once; also used by a later refresh. */
    public void store(Map<String, StencilFile> newFiles) {
        files = Map.copyOf(newFiles);
        loaded = true;
    }

    public Optional<StencilFile> get(String path) {
        return Optional.ofNullable(files.get(path));
    }

    public int size() {
        return files.size();
    }

    /** {@code false} until a zip has been stored (e.g. it could not be loaded at start); for health checks. */
    public boolean isLoaded() {
        return loaded;
    }

    @PreDestroy
    public void clear() {
        files = Map.of();
        loaded = false;
    }
}
