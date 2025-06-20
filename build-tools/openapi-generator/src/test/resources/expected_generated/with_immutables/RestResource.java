// DO NOT EDIT: generated by Dremio openapi-generator.
package com.dremio.test.dac.api;

import static javax.ws.rs.core.MediaType.APPLICATION_JSON;

import com.dremio.dac.annotations.Secured;
import com.dremio.test.api.GetBody;
import com.dremio.test.api.PostBody;
import com.dremio.test.api.PostChunk;
import com.dremio.test.api.ResponseContent;
import javax.annotation.Generated;
import javax.annotation.security.RolesAllowed;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Response;

@Secured
@RolesAllowed({"user", "admin"})
@Consumes(APPLICATION_JSON)
@Produces(APPLICATION_JSON)
@Path("/tests")
@Generated(value = { "com.dremio.tools.openapigenerator.java.JavaRestResourceGenerator" }, date = "2024-03-01T18:53:27Z")
public interface RestResource {

  /**
   * Test POST.
   *
   *   @param filter A Common Expression Language (CEL) expression. An intro to CEL can be found at
   *     https://github.com/google/cel-spec/blob/master/doc/intro.md.
  */
  @POST
  GetBody post(
    PostBody request,
    @QueryParam("filter") String filter);

  /**
   * Test GET.
   *
  */
  @GET
  @Path("/{path}/{otherPath}")
  GetBody get(
    @PathParam("path") String path,
    @PathParam("otherPath") Integer otherPath);

  /**
   * Test GET with in parameters.
   *
   *   @param id Id string parameter.
   *   @param offset Offset int parameter.
   *   @return Success.
  */
  @GET
  @Path("/with-in-param/{id}/{offset}")
  GetBody withInParam(
    @PathParam("id") String id,
    @PathParam("offset") Integer offset);

  /**
   * Test GET with in repeated parameters.
   *
   *   @param id Id string repeated parameter.
   *   @return Success.
  */
  @GET
  @Path("/with-in-repeated-param/{id}")
  GetBody withInRepeatedParam(
    @PathParam("id") String id);

  /**
   * Test another GET with the same path param name in reference.
   *
  */
  @GET
  @Path("/{path}")
  GetBody get1(
    @PathParam("path") String path);

  /**
   * Get without parameters.
  */
  @GET
  ResponseContent list();

  /**
   * Test PUT and reference to response.
  */
  @PUT
  ResponseContent put(
    PostBody request);

  /**
   * Test POST with streaming.
   *
  */
  @POST
  @Path("/post-chunked")
  Response postPost-chunked(
    PostBody request);
}
