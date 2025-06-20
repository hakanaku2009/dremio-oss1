// DO NOT EDIT: generated by Dremio openapi-generator.
package com.dremio.test.dac.api;

import com.dremio.dac.annotations.OpenApiResource;
import com.dremio.dac.api.ErrorResponseConverter;
import com.dremio.test.api.TestApiProto;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;

@OpenApiResource(serverPathSpec = "api/v4/*")
public class RestResourceImpl implements RestResource {

  @Override
  public TestApiProto.GetBody post(
    TestApiProto.PostBody request,
    String filter) {
    throw new WebApplicationException(
        ErrorResponseConverter.notImplemented("/tests"));
  }

  @Override
  public TestApiProto.GetBody get(
    String path,
    Integer otherPath) {
    throw new WebApplicationException(
        ErrorResponseConverter.notImplemented("/tests/{path}/{otherPath}"));
  }

  @Override
  public TestApiProto.GetBody withInParam(
    String id,
    Integer offset) {
    throw new WebApplicationException(
        ErrorResponseConverter.notImplemented("/tests/with-in-param/{id}/{offset}"));
  }

  @Override
  public TestApiProto.GetBody withInRepeatedParam(
    String id) {
    throw new WebApplicationException(
        ErrorResponseConverter.notImplemented("/tests/with-in-repeated-param/{id}"));
  }

  @Override
  public TestApiProto.GetBody get1(
    String path) {
    throw new WebApplicationException(
        ErrorResponseConverter.notImplemented("/tests/{path}"));
  }

  @Override
  public TestApiProto.ResponseContent list() {
    throw new WebApplicationException(
        ErrorResponseConverter.notImplemented("/tests"));
  }

  @Override
  public TestApiProto.ResponseContent put(
    TestApiProto.PostBody request) {
    throw new WebApplicationException(
        ErrorResponseConverter.notImplemented("/tests"));
  }

  @Override
  public Response postPost-chunked(
    TestApiProto.PostBody request) {
    throw new WebApplicationException(
        ErrorResponseConverter.notImplemented("/tests/post-chunked"));
  }
}
