package com.demo.rest;

import com.demo.ejb.faceframe.StencilCache;
import com.demo.ejb.faceframe.StencilFile;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.CacheControl;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Request;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;

/**
 * Serves the FaceFrame stencils from {@link StencilCache}:
 * {@code GET /api/face-frame/stencils/stencils.json} and {@code GET /api/face-frame/stencils/{file}}.
 */
@Path("/face-frame/stencils")
@RequestScoped
public class StencilResource {

    private static final int IMAGE_MAX_AGE_SECONDS = 24 * 60 * 60;
    private static final String NOSNIFF_HEADER = "X-Content-Type-Options";
    private static final String NOSNIFF = "nosniff";

    private final StencilCache cache;

    @Inject
    public StencilResource(StencilCache cache) {
        this.cache = cache;
    }

    /**
     * Not for direct use. Public because RESTEasy requires a public constructor when it registers the resource;
     * CDI then creates it with the {@link Inject} constructor (and uses this one for its proxy).
     */
    public StencilResource() {
        this(null);
    }

    @GET
    @Path("{path: .+}")
    public Response get(@PathParam("path") String path, @Context Request request) {
        return cache.get(path)
                .map(file -> respond(path, file, request))
                .orElseGet(() -> Response.status(Response.Status.NOT_FOUND).header(NOSNIFF_HEADER, NOSNIFF).build());
    }

    private static Response respond(String path, StencilFile file, Request request) {
        EntityTag etag = new EntityTag(file.etag());
        CacheControl cacheControl = cacheControl(path);
        Response.ResponseBuilder notModified = request.evaluatePreconditions(etag);
        if (notModified != null) {
            return notModified.cacheControl(cacheControl).header(NOSNIFF_HEADER, NOSNIFF).build();
        }
        return Response.ok((StreamingOutput) file::writeTo, file.contentType())
                .header(HttpHeaders.CONTENT_LENGTH, file.size())
                .tag(etag)
                .cacheControl(cacheControl)
                .header(NOSNIFF_HEADER, NOSNIFF)
                .build();
    }

    /** The manifest is revalidated on every load so new stencils appear; images are cached for a day. */
    private static CacheControl cacheControl(String path) {
        CacheControl cacheControl = new CacheControl();
        if (StencilCache.MANIFEST.equals(path)) {
            cacheControl.setNoCache(true);
        } else {
            cacheControl.setMaxAge(IMAGE_MAX_AGE_SECONDS);
        }
        return cacheControl;
    }
}
