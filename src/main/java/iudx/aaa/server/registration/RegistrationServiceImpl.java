package iudx.aaa.server.registration;

import static iudx.aaa.server.registration.Constants.CLIENT_SECRET_BYTES;
import static iudx.aaa.server.registration.Constants.COMPOSE_FAILURE;
import static iudx.aaa.server.registration.Constants.DEFAULT_CLIENT;
import static iudx.aaa.server.registration.Constants.ERR_DETAIL_NO_USER_PROFILE;
import static iudx.aaa.server.registration.Constants.ERR_DETAIL_ORG_ID_REQUIRED;
import static iudx.aaa.server.registration.Constants.ERR_DETAIL_ORG_NO_EXIST;
import static iudx.aaa.server.registration.Constants.ERR_DETAIL_ORG_NO_MATCH;
import static iudx.aaa.server.registration.Constants.ERR_DETAIL_ROLE_EXISTS;
import static iudx.aaa.server.registration.Constants.ERR_DETAIL_SEARCH_USR_INVALID_ROLE;
import static iudx.aaa.server.registration.Constants.ERR_DETAIL_USER_EXISTS;
import static iudx.aaa.server.registration.Constants.ERR_DETAIL_USER_NOT_FOUND;
import static iudx.aaa.server.registration.Constants.ERR_DETAIL_USER_NOT_KC;
import static iudx.aaa.server.registration.Constants.ERR_TITLE_NO_USER_PROFILE;
import static iudx.aaa.server.registration.Constants.ERR_TITLE_ORG_ID_REQUIRED;
import static iudx.aaa.server.registration.Constants.ERR_TITLE_ORG_NO_EXIST;
import static iudx.aaa.server.registration.Constants.ERR_TITLE_ORG_NO_MATCH;
import static iudx.aaa.server.registration.Constants.ERR_TITLE_ROLE_EXISTS;
import static iudx.aaa.server.registration.Constants.ERR_TITLE_SEARCH_USR_INVALID_ROLE;
import static iudx.aaa.server.registration.Constants.ERR_TITLE_USER_EXISTS;
import static iudx.aaa.server.registration.Constants.ERR_TITLE_USER_NOT_FOUND;
import static iudx.aaa.server.registration.Constants.ERR_TITLE_USER_NOT_KC;
import static iudx.aaa.server.registration.Constants.NIL_PHONE;
import static iudx.aaa.server.registration.Constants.NIL_UUID;
import static iudx.aaa.server.registration.Constants.NO_ORG_CHECK;
import static iudx.aaa.server.registration.Constants.PROVIDER_PENDING_MESG;
import static iudx.aaa.server.registration.Constants.RESP_CLIENT_ARR;
import static iudx.aaa.server.registration.Constants.RESP_CLIENT_ID;
import static iudx.aaa.server.registration.Constants.RESP_CLIENT_NAME;
import static iudx.aaa.server.registration.Constants.RESP_CLIENT_SC;
import static iudx.aaa.server.registration.Constants.RESP_EMAIL;
import static iudx.aaa.server.registration.Constants.RESP_ORG;
import static iudx.aaa.server.registration.Constants.RESP_PHONE;
import static iudx.aaa.server.registration.Constants.SQL_CREATE_CLIENT;
import static iudx.aaa.server.registration.Constants.SQL_CREATE_ROLE;
import static iudx.aaa.server.registration.Constants.SQL_CREATE_USER;
import static iudx.aaa.server.registration.Constants.SQL_FIND_ORG_BY_ID;
import static iudx.aaa.server.registration.Constants.SQL_FIND_USER_BY_KC_ID;
import static iudx.aaa.server.registration.Constants.SQL_GET_ALL_ORGS;
import static iudx.aaa.server.registration.Constants.SQL_GET_CLIENTS_FORMATTED;
import static iudx.aaa.server.registration.Constants.SQL_GET_KC_ID_FROM_ARR;
import static iudx.aaa.server.registration.Constants.SQL_GET_ORG_DETAILS;
import static iudx.aaa.server.registration.Constants.SQL_GET_PHONE_JOIN_ORG;
import static iudx.aaa.server.registration.Constants.SQL_GET_UID_ORG_ID_CHECK_ROLE;
import static iudx.aaa.server.registration.Constants.SQL_UPDATE_ORG_ID;
import static iudx.aaa.server.registration.Constants.SUCC_TITLE_CREATED_USER;
import static iudx.aaa.server.registration.Constants.SUCC_TITLE_ORG_READ;
import static iudx.aaa.server.registration.Constants.SUCC_TITLE_UPDATED_USER_ROLES;
import static iudx.aaa.server.registration.Constants.SUCC_TITLE_USER_READ;
import static iudx.aaa.server.registration.Constants.SUCC_TITLE_USER_FOUND;
import static iudx.aaa.server.registration.Constants.URN_ALREADY_EXISTS;
import static iudx.aaa.server.registration.Constants.URN_INVALID_INPUT;
import static iudx.aaa.server.registration.Constants.URN_INVALID_ROLE;
import static iudx.aaa.server.registration.Constants.URN_MISSING_INFO;
import static iudx.aaa.server.registration.Constants.URN_SUCCESS;
import static iudx.aaa.server.registration.Constants.UUID_REGEX;

import io.vertx.core.AsyncResult;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import iudx.aaa.server.apiserver.RegistrationRequest;
import iudx.aaa.server.apiserver.Response;
import iudx.aaa.server.apiserver.Response.ResponseBuilder;
import iudx.aaa.server.apiserver.RoleStatus;
import iudx.aaa.server.apiserver.Roles;
import iudx.aaa.server.apiserver.UpdateProfileRequest;
import iudx.aaa.server.apiserver.User;
import iudx.aaa.server.apiserver.User.UserBuilder;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The Registration Service Implementation.
 * <h1>Registration Service Implementation</h1>
 * <p>
 * The Registration Service implementation in the IUDX AAA Server implements the definitions of the
 * {@link iudx.aaa.server.registration.RegistrationService}.
 * </p>
 * 
 */

public class RegistrationServiceImpl implements RegistrationService {

  private static final Logger LOGGER = LogManager.getLogger(RegistrationServiceImpl.class);

  private PgPool pool;
  private KcAdmin kc;

  public RegistrationServiceImpl(PgPool pool, KcAdmin kc) {
    this.pool = pool;
    this.kc = kc;
  }

  @Override
  public RegistrationService createUser(RegistrationRequest request, User user,
      Handler<AsyncResult<JsonObject>> handler) {

    LOGGER.debug("Info : " + LOGGER.getName() + " : Request received");

    List<Roles> requestedRoles = request.getRoles();
    UUID orgId = UUID.fromString(request.getOrgId());
    final String phone = request.getPhone();

    if (requestedRoles.contains(Roles.PROVIDER) || requestedRoles.contains(Roles.DELEGATE)) {
      if (orgId.toString().equals(NIL_UUID)) {
        Response r = new ResponseBuilder().status(400).type(URN_MISSING_INFO)
            .title(ERR_TITLE_ORG_ID_REQUIRED).detail(ERR_DETAIL_ORG_ID_REQUIRED).build();
        handler.handle(Future.succeededFuture(r.toJson()));
        return this;
      }
    }

    Map<Roles, RoleStatus> roles = new HashMap<Roles, RoleStatus>();

    for (Roles r : requestedRoles) {
      if (r == Roles.PROVIDER) {
        roles.put(r, RoleStatus.PENDING);
      } else {
        roles.put(r, RoleStatus.APPROVED);
      }
    }
    /* TODO later on, can check if user ID is not NIL_UUID */
    Future<Integer> checkUserExist =
        pool.withConnection(conn -> conn.preparedQuery(SQL_FIND_USER_BY_KC_ID)
            .execute(Tuple.of(user.getKeycloakId())).map(rows -> rows.size()));

    Future<String> email = kc.getEmailId(user.getKeycloakId());

    Future<String> checkOrgExist;
    String orgIdToSet;

    if (roles.containsKey(Roles.PROVIDER) || roles.containsKey(Roles.DELEGATE)) {
      orgIdToSet = request.getOrgId();
      checkOrgExist = pool.withConnection(
          conn -> conn.preparedQuery(SQL_GET_ORG_DETAILS).execute(Tuple.of(orgId.toString())).map(
              rows -> rows.rowCount() > 0 ? rows.iterator().next().toJson().toString() : null));
    } else {
      checkOrgExist = Future.succeededFuture(NO_ORG_CHECK);
      orgIdToSet = null;
    }

    /* Compose the previous futures to validate. Returns email ID of the user if successful */
    Future<String> validation =
        CompositeFuture.all(checkUserExist, email, checkOrgExist).compose(arr -> {

          int userRow = (int) arr.list().get(0);
          String emailId = (String) arr.list().get(1);
          String orgDetails = (String) arr.list().get(2);

          if (userRow != 0) {
            Response r = new ResponseBuilder().status(409).type(URN_ALREADY_EXISTS)
                .title(ERR_TITLE_USER_EXISTS).detail(ERR_DETAIL_USER_EXISTS).build();
            handler.handle(Future.succeededFuture(r.toJson()));
            return Future.failedFuture(COMPOSE_FAILURE);
          }

          if (emailId.length() == 0) {
            Response r = new ResponseBuilder().status(400).type(URN_INVALID_INPUT)
                .title(ERR_TITLE_USER_NOT_KC).detail(ERR_DETAIL_USER_NOT_KC).build();
            handler.handle(Future.succeededFuture(r.toJson()));
            return Future.failedFuture(COMPOSE_FAILURE);
          }

          String emailDomain = emailId.split("@")[1];

          if (orgDetails == null) {
            Response r = new ResponseBuilder().status(400).type(URN_INVALID_INPUT)
                .title(ERR_TITLE_ORG_NO_EXIST).detail(ERR_DETAIL_ORG_NO_EXIST).build();
            handler.handle(Future.succeededFuture(r.toJson()));
            return Future.failedFuture(COMPOSE_FAILURE);
          } else if (orgDetails == NO_ORG_CHECK) {
            return Future.succeededFuture(emailId);
          }

          String url = new JsonObject(orgDetails).getString("url");

          if (!url.equals(emailDomain)) {
            Response r = new ResponseBuilder().status(400).type(URN_INVALID_INPUT)
                .title(ERR_TITLE_ORG_NO_MATCH).detail(ERR_DETAIL_ORG_NO_MATCH).build();
            handler.handle(Future.succeededFuture(r.toJson()));
            return Future.failedFuture(COMPOSE_FAILURE);
          }

          return Future.succeededFuture(emailId);
        });

    /* create client ID and random client secret */
    UUID clientId = UUID.randomUUID();
    SecureRandom random = new SecureRandom();
    byte[] randBytes = new byte[CLIENT_SECRET_BYTES];
    random.nextBytes(randBytes);
    String clientSecret = Hex.encodeHexString(randBytes);

    List<Roles> rolesForKc =
        requestedRoles.stream().filter(x -> x != Roles.PROVIDER).collect(Collectors.toList());

    Promise<UUID> genUserId = Promise.promise();
    Future<UUID> userId = genUserId.future();

    /*
     * Function to form tuple for create user query. The email ID of the user is taken as input
     */
    Function<String, Tuple> createUserTup = (emailId) -> {
      String hash = DigestUtils.sha1Hex(emailId.getBytes());
      String emailHash = emailId.split("@")[1] + '/' + hash;
      return Tuple.of(phone, orgIdToSet, emailHash, user.getKeycloakId());
    };

    /*
     * Function to complete generated User ID promise and to create list of tuples for role creation
     * batch query
     */
    Function<UUID, List<Tuple>> createRoleTup = (id) -> {
      genUserId.complete(id);
      return roles.entrySet().stream()
          .map(p -> Tuple.of(id, p.getKey().name(), p.getValue().name()))
          .collect(Collectors.toList());
    };

    /* Function to hash client secret, and create tuple for client creation query */
    Supplier<Tuple> createClientTup = () -> {
      String hashedClientSecret = DigestUtils.sha512Hex(clientSecret);
      return Tuple.of(userId.result(), clientId, hashedClientSecret, DEFAULT_CLIENT);
    };

    /* Insertion into users, roles, clients tables and add roles to Keycloak */
    Future<Void> query = validation.compose(emailId -> pool.withTransaction(
        conn -> conn.preparedQuery(SQL_CREATE_USER).execute(createUserTup.apply(emailId))
            .map(rows -> rows.iterator().next().getUUID("id")).map(uid -> createRoleTup.apply(uid))
            .compose(roleDetails -> conn.preparedQuery(SQL_CREATE_ROLE).executeBatch(roleDetails))
            .map(success -> createClientTup.get())
            .compose(clientDetails -> conn.preparedQuery(SQL_CREATE_CLIENT).execute(clientDetails))
            .compose(success -> kc.modifyRoles(user.getKeycloakId(), rolesForKc))));

    query.onSuccess(success -> {
      User u =
          new UserBuilder().name(user.getName().get("firstName"), user.getName().get("lastName"))
              .roles(rolesForKc).keycloakId(user.getKeycloakId()).userId(userId.result()).build();

      JsonObject clientDetails = new JsonObject().put(RESP_CLIENT_NAME, DEFAULT_CLIENT)
          .put(RESP_CLIENT_ID, clientId.toString()).put(RESP_CLIENT_SC, clientSecret);

      JsonArray clients = new JsonArray().add(clientDetails);
      JsonObject payload =
          u.toJsonResponse().put(RESP_CLIENT_ARR, clients).put(RESP_EMAIL, validation.result());

      if (phone != NIL_PHONE) {
        payload.put(RESP_PHONE, phone);
      }

      if (checkOrgExist.result() != NO_ORG_CHECK) {
        payload.put(RESP_ORG, new JsonObject(checkOrgExist.result()));
      }

      String title = SUCC_TITLE_CREATED_USER;
      if (requestedRoles.contains(Roles.PROVIDER)) {
        title = title + PROVIDER_PENDING_MESG;
      }

      Response r = new ResponseBuilder().type(URN_SUCCESS).title(title).status(201)
          .objectResults(payload).build();
      handler.handle(Future.succeededFuture(r.toJson()));

      LOGGER.info("Created user profile for " + userId.result() + " with roles "
          + request.getRoles().toString());
    }).onFailure(e -> {
      if (e.getMessage().equals(COMPOSE_FAILURE)) {
        return; // do nothing
      }
      LOGGER.error(e.getMessage());
      handler.handle(Future.failedFuture("Internal error"));
    });

    return this;
  }

  @Override
  public RegistrationService listUser(User user, JsonObject searchUserDetails,
      JsonObject authDelegateDetails, Handler<AsyncResult<JsonObject>> handler) {
    LOGGER.debug("Info : " + LOGGER.getName() + " : Request received");

    if (user.getUserId().equals(NIL_UUID)) {
      Response r = new ResponseBuilder().status(404).type(URN_MISSING_INFO)
          .title(ERR_TITLE_NO_USER_PROFILE).detail(ERR_DETAIL_NO_USER_PROFILE).build();
      handler.handle(Future.succeededFuture(r.toJson()));
      return this;
    }

    /* If it's a search user flow */
    if (!searchUserDetails.isEmpty()) {
      Promise<JsonObject> promise = Promise.promise();
      Boolean isAuthDelegate = !authDelegateDetails.isEmpty();
      searchUser(user, searchUserDetails, isAuthDelegate, promise);
      promise.future().onComplete(result -> handler.handle(result));
      return this;
    }

    Future<JsonObject> phoneOrgDetails =
        pool.withConnection(conn -> conn.preparedQuery(SQL_GET_PHONE_JOIN_ORG)
            .execute(Tuple.of(user.getUserId())).map(rows -> rows.iterator().next().toJson()));

    Future<String> email = kc.getEmailId(user.getKeycloakId());

    Collector<Row, ?, List<JsonObject>> clientDetails =
        Collectors.mapping(row -> row.toJson(), Collectors.toList());

    Future<List<JsonObject>> clientQuery =
        pool.withConnection(conn -> conn.preparedQuery(SQL_GET_CLIENTS_FORMATTED)
            .collecting(clientDetails).execute(Tuple.of(user.getUserId())).map(res -> res.value()));

    CompositeFuture.all(phoneOrgDetails, clientQuery, email).onSuccess(obj -> {

      JsonObject details = (JsonObject) obj.list().get(0);
      @SuppressWarnings("unchecked")
      List<JsonObject> clients = (List<JsonObject>) obj.list().get(1);
      String emailId = (String) obj.list().get(2);

      if (emailId.length() == 0) {
        Response r = new ResponseBuilder().status(400).type(URN_INVALID_INPUT)
            .title(ERR_TITLE_USER_NOT_KC).detail(ERR_DETAIL_USER_NOT_KC).build();
        handler.handle(Future.succeededFuture(r.toJson()));
        return;
      }

      JsonObject response = user.toJsonResponse();
      response.put(RESP_EMAIL, emailId);
      response.put(RESP_CLIENT_ARR, new JsonArray(clients));

      String phone = (String) details.remove("phone");
      if (!phone.equals(NIL_PHONE)) {
        response.put(RESP_PHONE, phone);
      }

      /* details will have only org details or or only null */
      if (details.getString("url") != null) {
        response.put(RESP_ORG, details);
      }

      Response r = new ResponseBuilder().type(URN_SUCCESS).title(SUCC_TITLE_USER_READ).status(200)
          .objectResults(response).build();
      handler.handle(Future.succeededFuture(r.toJson()));
    }).onFailure(e -> {
      if (e.getMessage().equals(COMPOSE_FAILURE)) {
        return; // do nothing
      }
      LOGGER.error(e.getMessage());
      handler.handle(Future.failedFuture("Internal error"));
    });

    return this;
  }

  @Override
  public RegistrationService updateUser(UpdateProfileRequest request, User user,
      Handler<AsyncResult<JsonObject>> handler) {
    LOGGER.debug("Info : " + LOGGER.getName() + " : Request received");

    if (user.getUserId().equals(NIL_UUID)) {
      Response r = new ResponseBuilder().status(404).type(URN_MISSING_INFO)
          .title(ERR_TITLE_NO_USER_PROFILE).detail(ERR_DETAIL_NO_USER_PROFILE).build();
      handler.handle(Future.succeededFuture(r.toJson()));
      return this;
    }

    List<Roles> requestedRoles = request.getRoles();
    List<Roles> registeredRoles = user.getRoles();
    String orgId = request.getOrgId();

    Map<Roles, RoleStatus> roles = new HashMap<Roles, RoleStatus>();

    for (Roles r : requestedRoles) {
      roles.put(r, RoleStatus.APPROVED);
    }

    List<Roles> duplicate =
        registeredRoles.stream().filter(requestedRoles::contains).collect(Collectors.toList());

    if (duplicate.size() != 0) {
      String dupRoles =
          duplicate.stream().map(str -> str.name().toLowerCase()).collect(Collectors.joining(", "));

      Response r = new ResponseBuilder().status(409).type(URN_ALREADY_EXISTS)
          .title(ERR_TITLE_ROLE_EXISTS).detail(ERR_DETAIL_ROLE_EXISTS + dupRoles).build();
      handler.handle(Future.succeededFuture(r.toJson()));
      return this;
    }

    Future<String> email = kc.getEmailId(user.getKeycloakId());
    Future<String> checkOrgRequired;

    /* orgId is needed always for delegate reg, even if the user has registered for provider role */
    if (requestedRoles.contains(Roles.DELEGATE)) {
      if (orgId.toString().equals(NIL_UUID)) {
        Response r = new ResponseBuilder().status(400).type(URN_MISSING_INFO)
            .title(ERR_TITLE_ORG_ID_REQUIRED).detail(ERR_DETAIL_ORG_ID_REQUIRED).build();
        handler.handle(Future.succeededFuture(r.toJson()));
        return this;
      }
      checkOrgRequired = pool.withConnection(
          conn -> conn.preparedQuery(SQL_FIND_ORG_BY_ID).execute(Tuple.of(orgId.toString())).map(
              rows -> rows.iterator().hasNext() ? rows.iterator().next().getString("url") : null));
    } else {
      checkOrgRequired = Future.succeededFuture(NO_ORG_CHECK);
    }

    Future<Void> validateOrg = CompositeFuture.all(checkOrgRequired, email).compose(x -> {
      String url = (String) x.list().get(0);
      String emailId = (String) x.list().get(1);

      String emailDomain = emailId.split("@")[1];

      if (emailId.length() == 0) {
        Response r = new ResponseBuilder().status(400).type(URN_INVALID_INPUT)
            .title(ERR_TITLE_USER_NOT_KC).detail(ERR_DETAIL_USER_NOT_KC).build();
        handler.handle(Future.succeededFuture(r.toJson()));
        return Future.failedFuture(COMPOSE_FAILURE);
      }

      if (url == null) {
        Response r = new ResponseBuilder().status(400).type(URN_INVALID_INPUT)
            .title(ERR_TITLE_ORG_NO_EXIST).detail(ERR_DETAIL_ORG_NO_EXIST).build();
        handler.handle(Future.succeededFuture(r.toJson()));
        return Future.failedFuture(COMPOSE_FAILURE);

      } else if (!url.equals(emailDomain) && !url.equals(NO_ORG_CHECK)) {
        Response r = new ResponseBuilder().status(400).type(URN_INVALID_INPUT)
            .title(ERR_TITLE_ORG_NO_MATCH).detail(ERR_DETAIL_ORG_NO_MATCH).build();
        handler.handle(Future.succeededFuture(r.toJson()));
        return Future.failedFuture(COMPOSE_FAILURE);
      }

      return Future.succeededFuture();
    });

    List<Roles> rolesForKc =
        requestedRoles.stream().filter(x -> x != Roles.PROVIDER).collect(Collectors.toList());

    List<Tuple> roleDetails = roles.entrySet().stream()
        .map(p -> Tuple.of(user.getUserId(), p.getKey().name(), p.getValue().name()))
        .collect(Collectors.toList());

    /* supplier to create tuple for org update */
    Supplier<Tuple> updateOrgIdTup = () -> {
      if (checkOrgRequired.result() == NO_ORG_CHECK) {
        return Tuple.of(null, user.getUserId());
      }
      return Tuple.of(request.getOrgId(), user.getUserId());
    };

    Future<Void> query = validateOrg.compose(res -> pool
        .withTransaction(conn -> conn.preparedQuery(SQL_CREATE_ROLE).executeBatch(roleDetails)
            .compose(success -> conn.preparedQuery(SQL_UPDATE_ORG_ID).execute(updateOrgIdTup.get()))
            .compose(success -> kc.modifyRoles(user.getKeycloakId(), rolesForKc))));

    /* After successful insertion, get user details for response */
    Future<JsonObject> phoneOrgDetails =
        query.compose(x -> pool.withConnection(conn -> conn.preparedQuery(SQL_GET_PHONE_JOIN_ORG)
            .execute(Tuple.of(user.getUserId())).map(rows -> rows.iterator().next().toJson())));

    Collector<Row, ?, List<JsonObject>> clientDetails =
        Collectors.mapping(row -> row.toJson(), Collectors.toList());

    Future<List<JsonObject>> clientQuery = query.compose(x -> pool.withConnection(
        conn -> conn.preparedQuery(SQL_GET_CLIENTS_FORMATTED).collecting(clientDetails)
            .execute(Tuple.of(user.getUserId())).map(res -> res.value())));

    CompositeFuture.all(phoneOrgDetails, clientQuery).onSuccess(obj -> {
      JsonObject details = (JsonObject) obj.list().get(0);
      @SuppressWarnings("unchecked")
      List<JsonObject> clients = (List<JsonObject>) obj.list().get(1);

      List<Roles> approvedRoles = new ArrayList<Roles>();
      approvedRoles.addAll(user.getRoles());
      approvedRoles.addAll(rolesForKc);

      User u = new UserBuilder()
          .name(user.getName().get("firstName"), user.getName().get("lastName"))
          .roles(approvedRoles).keycloakId(user.getKeycloakId()).userId(user.getUserId()).build();

      JsonObject response = u.toJsonResponse();
      response.put(RESP_EMAIL, email.result());
      response.put(RESP_CLIENT_ARR, new JsonArray(clients));

      String phone = (String) details.remove("phone");
      if (!phone.equals(NIL_PHONE)) {
        response.put(RESP_PHONE, phone);
      }

      /* details will have only org details or or only null */
      if (details.getString("url") != null) {
        response.put(RESP_ORG, details);
      }

      LOGGER.info("Updated user profile for " + u.getUserId().toString() + " with roles "
          + request.getRoles().toString());

      Response r = new ResponseBuilder().type(URN_SUCCESS).title(SUCC_TITLE_UPDATED_USER_ROLES)
          .status(200).objectResults(response).build();
      handler.handle(Future.succeededFuture(r.toJson()));
    }).onFailure(e -> {
      if (e.getMessage().equals(COMPOSE_FAILURE)) {
        return; // do nothing
      }
      LOGGER.error(e.getMessage());
      handler.handle(Future.failedFuture("Internal error"));
    });

    return this;
  }

  @Override
  public RegistrationService listOrganization(Handler<AsyncResult<JsonObject>> handler) {
    LOGGER.debug("Info : " + LOGGER.getName() + " : Request received");

    Collector<Row, ?, List<JsonObject>> orgCollect =
        Collectors.mapping(row -> row.toJson(), Collectors.toList());

    pool.withConnection(conn -> conn.preparedQuery(SQL_GET_ALL_ORGS).collecting(orgCollect)
        .execute().map(rows -> rows.value()).onSuccess(obj -> {
          JsonArray resp = new JsonArray(obj);

          Response r = new ResponseBuilder().type(URN_SUCCESS).title(SUCC_TITLE_ORG_READ)
              .status(200).arrayResults(resp).build();
          handler.handle(Future.succeededFuture(r.toJson()));
        }).onFailure(e -> {
          LOGGER.error(e.getMessage());
          handler.handle(Future.failedFuture("Internal error"));
        }));

    return this;
  }

  @Override
  public RegistrationService getUserDetails(List<String> userIds,
      Handler<AsyncResult<JsonObject>> handler) {
    LOGGER.debug("Info : " + LOGGER.getName() + " : Request received");

    if (userIds.isEmpty()) {
      handler.handle(Future.succeededFuture(new JsonObject()));
      return this;
    }

    Set<UUID> unique = new HashSet<UUID>();
    Promise<Map<String, String>> userToKc = Promise.promise();

    for (String id : userIds) {
      if (id == null || !id.matches(UUID_REGEX)) {
        handler.handle(Future.failedFuture("Invalid UUID"));
        return this;
      }
      unique.add(UUID.fromString(id));
    }

    List<UUID> ids = new ArrayList<UUID>(unique);

    Collector<Row, ?, Map<String, String>> collect = Collectors
        .toMap(row -> row.getUUID("id").toString(), row -> row.getUUID("keycloak_id").toString());

    int size = ids.size();
    /* Function to complete user-KC map promise and create list of Keycloak IDs */
    Function<Map<String, String>, Future<List<String>>> getKcIdsList = (u2k) -> {
      if (u2k.size() != size) {
        handler.handle(Future.failedFuture("Invalid user ID"));
        return Future.failedFuture(COMPOSE_FAILURE);
      }

      userToKc.complete(u2k);
      List<String> kcIds =
          u2k.entrySet().stream().map(id -> id.getValue()).collect(Collectors.toList());
      return Future.succeededFuture(kcIds);
    };

    Tuple tup = Tuple.of(ids.toArray(UUID[]::new));
    Future<Map<String, JsonObject>> details = pool.withConnection(conn -> conn
        .preparedQuery(SQL_GET_KC_ID_FROM_ARR).collecting(collect).execute(tup)
        .compose(res -> getKcIdsList.apply(res.value())).compose(kcIds -> kc.getDetails(kcIds)));

    /* 'merge' userId-KcId and KcId-details maps */
    details.onSuccess(kcToDetails -> {
      Map<String, String> user2kc = userToKc.future().result();
      JsonObject userDetails = new JsonObject();

      user2kc.forEach((userId, kcId) -> userDetails.put(userId, kcToDetails.get(kcId)));
      handler.handle(Future.succeededFuture(userDetails));
    }).onFailure(e -> {
      if (e.getMessage().equals(COMPOSE_FAILURE)) {
        return; // do nothing
      }
      LOGGER.error(e.getMessage());
      handler.handle(Future.failedFuture("Internal error"));
    });

    return this;
  }

  public void searchUser(User user, JsonObject searchUserDetails, Boolean isAuthDelegate,
      Promise<JsonObject> promise) {

    /* Create error denoting email+role does not exist */
    Supplier<Response> getSearchErr = () -> {
      return new ResponseBuilder().type(URN_INVALID_INPUT).title(ERR_TITLE_USER_NOT_FOUND)
          .status(404).detail(ERR_DETAIL_USER_NOT_FOUND).build();
    };

    List<Roles> roles = user.getRoles();

    if (!(roles.contains(Roles.PROVIDER) || roles.contains(Roles.ADMIN)
        || (roles.contains(Roles.DELEGATE) && isAuthDelegate))) {
      Response r = new ResponseBuilder().status(401).type(URN_INVALID_ROLE)
          .title(ERR_TITLE_SEARCH_USR_INVALID_ROLE).detail(ERR_DETAIL_SEARCH_USR_INVALID_ROLE)
          .build();
      promise.complete(r.toJson());
      return;
    }

    String email = searchUserDetails.getString("email").toLowerCase();
    Roles role = Roles.valueOf(searchUserDetails.getString("role").toUpperCase());

    Future<JsonObject> foundUser = kc.findUserByEmail(email);

    Future<UUID> exists = foundUser.compose(res -> {
      if (res.isEmpty()) {
        promise.complete(getSearchErr.get().toJson());
        return Future.failedFuture(COMPOSE_FAILURE);
      }

      UUID keycloakId = UUID.fromString(res.getString("keycloakId"));
      return Future.succeededFuture(keycloakId);
    });

    /*
     * Get user ID and org ID (if applicable) if the user + role exists (user profile is there and
     * user has the requested role)
     */
    Future<JsonObject> getUserId = exists.compose(res -> pool.withConnection(
        conn -> conn.preparedQuery(SQL_GET_UID_ORG_ID_CHECK_ROLE).execute(Tuple.of(res, role)).map(
            row -> row.iterator().hasNext() ? row.iterator().next().toJson() : new JsonObject())));

    Future<JsonObject> getOrgIfNeeded = getUserId.compose(res -> {
      if (res.isEmpty()) {
        promise.complete(getSearchErr.get().toJson());
        return Future.failedFuture(COMPOSE_FAILURE);
      }

      if (res.getString("organization_id") != null) {
        return pool.withConnection(conn -> conn.preparedQuery(SQL_GET_ORG_DETAILS)
            .execute(Tuple.of(UUID.fromString(res.getString("organization_id"))))
            .map(row -> row.iterator().next().toJson()));
      }

      return Future.succeededFuture(new JsonObject());
    });

    getOrgIfNeeded.onSuccess(res -> {
      JsonObject response = new JsonObject();

      JsonObject userDetails = foundUser.result();
      String userId = getUserId.result().getString("id");
      response.put(RESP_EMAIL, userDetails.getString("email"));
      response.put("userId", userId);
      response.put("name", userDetails.getJsonObject("name"));

      if (!res.isEmpty()) {
        response.put(RESP_ORG, res);
      }

      Response r = new ResponseBuilder().type(URN_SUCCESS).title(SUCC_TITLE_USER_FOUND).status(200)
          .objectResults(response).build();
      promise.complete(r.toJson());
    }).onFailure(e -> {
      if (e.getMessage().equals(COMPOSE_FAILURE)) {
        return; // do nothing
      }
      LOGGER.error(e.getMessage());
      promise.fail("Internal error");
    });

    return;
  }
}
