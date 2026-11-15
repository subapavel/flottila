package io.flottila.mapping.service;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

import io.flottila.mapping.api.EngineFacade;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * {@code GET /api/catalogue} (ADR-0026, ADR-0015): the function catalogue as the engine packs it.
 * Its bytes never pass through Jackson, and it has no ETag: the catalogue changes only with the
 * build.
 */
@jakarta.ws.rs.Path("/api/catalogue")
public class CatalogueResource {

  /** The function catalogue's schema, next to {@code openapi.json} in {@code contract/}. */
  static final String CATALOGUE = "./function-catalogue.schema.json";

  private final EngineFacade facade;

  @Inject
  CatalogueResource(EngineFacade facade) {
    this.facade = facade;
  }

  @GET
  @Produces(APPLICATION_JSON)
  @APIResponse(
      responseCode = "200",
      description = "The function catalogue, keyed by function id",
      content = @Content(mediaType = APPLICATION_JSON, schema = @Schema(ref = CATALOGUE)))
  public Response get() {
    return Response.ok(facade.catalogue(), APPLICATION_JSON).build();
  }
}
