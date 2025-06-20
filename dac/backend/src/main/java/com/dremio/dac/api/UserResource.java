/*
 * Copyright (C) 2017-2019 Dremio Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dremio.dac.api;

import static javax.ws.rs.core.MediaType.APPLICATION_JSON;
import static javax.ws.rs.core.MediaType.APPLICATION_JSON_TYPE;
import static javax.ws.rs.core.Response.Status.NOT_FOUND;

import com.dremio.common.exceptions.UserException;
import com.dremio.dac.annotations.APIResource;
import com.dremio.dac.annotations.Secured;
import com.dremio.dac.model.common.DACUnauthorizedException;
import com.dremio.dac.model.spaces.HomeName;
import com.dremio.dac.model.spaces.HomePath;
import com.dremio.dac.server.GenericErrorMessage;
import com.dremio.service.namespace.NamespaceService;
import com.dremio.service.namespace.space.proto.HomeConfig;
import com.dremio.service.users.SimpleUser;
import com.dremio.service.users.UserNotFoundException;
import com.dremio.service.users.UserService;
import com.dremio.service.users.proto.UID;
import com.google.common.base.Strings;
import java.io.IOException;
import java.util.Optional;
import javax.annotation.security.RolesAllowed;
import javax.inject.Inject;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.NotSupportedException;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;

/** User API resource */
@APIResource
@Secured
@Path("/user")
@Consumes(APPLICATION_JSON)
@Produces(APPLICATION_JSON)
public class UserResource {
  private static final org.slf4j.Logger logger =
      org.slf4j.LoggerFactory.getLogger(UserResource.class);

  private final UserService userGroupService;
  private final NamespaceService namespaceService;
  private final SecurityContext securityContext;

  @Inject
  public UserResource(
      UserService userGroupService,
      NamespaceService namespaceService,
      @Context SecurityContext securityContext) {
    this.userGroupService = userGroupService;
    this.securityContext = securityContext;
    this.namespaceService = namespaceService;
  }

  @RolesAllowed({"admin", "user"})
  @GET
  @Path("/{id}")
  public User getUser(@PathParam("id") String id) throws UserNotFoundException {
    return User.fromUser(userGroupService.getUser(new UID(id)));
  }

  @RolesAllowed({"admin", "user"})
  @POST
  public User createUser(User user) throws IOException {
    final com.dremio.service.users.User userConfig =
        UserResource.addUser(
            of(user, Optional.empty()),
            user.getPassword(),
            userGroupService,
            namespaceService,
            this.securityContext);

    return User.fromUser(userConfig);
  }

  // The code is taken from from v2 api. I put it here as static to re-use it in both places. As
  // soon as v2 api would
  // be deprecated we should convert this method to private instance method.
  public static com.dremio.service.users.User addUser(
      com.dremio.service.users.User newUser,
      String password,
      UserService userService,
      NamespaceService namespaceService,
      SecurityContext context)
      throws IOException {
    final com.dremio.service.users.User savedUser;
    if (password != null) {
      savedUser = userService.createUser(newUser, password);
    } else {
      savedUser = userService.createUser(newUser);
    }
    final String userName = savedUser.getUserName();

    try {
      /* Creating a name space for the new user, if the caller is an ADMIN (to maintain original behavior of the API,
      and avoid conditional checks).
      For callers with non ADMIN roles, the home space for the new users get created when they first login-in. */
      if (context.isUserInRole("admin")) {
        namespaceService.addOrUpdateHome(
            new HomePath(HomeName.getUserHomePath(userName)).toNamespaceKey(),
            new HomeConfig().setOwner(userName));
      }
    } catch (Exception e) {
      try {
        userService.deleteUser(savedUser.getUserName(), savedUser.getVersion());
      } catch (UserNotFoundException e1) {
        logger.warn(
            "Could not delete a user {} after failing to create corresponding home space",
            userName);
      } finally {
        throw UserException.unsupportedError()
            .message(
                "Unable to create user '%s'. There may already be a user with the same name but different casing.",
                savedUser.getUserName())
            .addContext("Cause", e.getMessage())
            .build();
      }
    }

    return savedUser;
  }

  @RolesAllowed({"admin", "user"})
  @PUT
  @Path("/{id}")
  public User updateUser(User user, @PathParam("id") String id)
      throws UserNotFoundException, IOException, DACUnauthorizedException {
    if (Strings.isNullOrEmpty(id)) {
      throw new IllegalArgumentException("No user id provided");
    }
    final com.dremio.service.users.User savedUser = userGroupService.getUser(new UID(id));

    if (!savedUser.getUserName().equals(user.getName())) {
      throw new NotSupportedException("Changing of user name is not supported");
    }

    final com.dremio.service.users.User userConfig =
        userGroupService.updateUser(of(user, Optional.of(id)), user.getPassword());

    return User.fromUser(userConfig);
  }

  @RolesAllowed({"admin", "user"})
  @GET
  @Path("/by-name/{name}")
  public Response getUserByName(@PathParam("name") String name) {
    try {
      return Response.ok(User.fromUser(userGroupService.getUser(name))).build();
    } catch (UserNotFoundException e) {
      return Response.status(NOT_FOUND)
          .entity(new GenericErrorMessage(e.getMessage()))
          .type(APPLICATION_JSON_TYPE)
          .build();
    }
  }

  private com.dremio.service.users.User of(User user, Optional<String> userId) {
    final SimpleUser.Builder userBuilder =
        SimpleUser.newBuilder()
            .setUserName(user.getName())
            .setFirstName(user.getFirstName())
            .setLastName(user.getLastName())
            .setEmail(user.getEmail())
            .setVersion(user.getTag());

    if (userId.isPresent()) {
      userBuilder.setUID(new UID(userId.get()));
    }

    return userBuilder.build();
  }
}
