package iudx.aaa.server.token;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.JWTOptions;
import io.vertx.ext.auth.authentication.TokenCredentials;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import iudx.aaa.server.apiserver.IntrospectToken;
import iudx.aaa.server.apiserver.RequestToken;
import iudx.aaa.server.apiserver.Response;
import iudx.aaa.server.apiserver.RevokeToken;
import iudx.aaa.server.apiserver.User;
import iudx.aaa.server.apiserver.Response.ResponseBuilder;
import iudx.aaa.server.policy.PolicyService;
import java.util.List;
import java.util.stream.Collectors;
import static iudx.aaa.server.registration.Constants.ERR_DETAIL_NO_USER_PROFILE;
import static iudx.aaa.server.registration.Constants.ERR_TITLE_NO_USER_PROFILE;
import static iudx.aaa.server.registration.Constants.NIL_UUID;
import static iudx.aaa.server.registration.Constants.URN_MISSING_INFO;
import static iudx.aaa.server.token.Constants.*;

/**
 * The Token Service Implementation.
 * <h1>Token Service Implementation</h1>
 * <p>
 * The Token Service implementation in the IUDX AAA Server implements the definitions of the
 * {@link iudx.aaa.server.token.TokenService}.
 * </p>
 * 
 * @version 1.0
 * @since 2020-12-15
 */

public class TokenServiceImpl implements TokenService {

  private static final Logger LOGGER = LogManager.getLogger(TokenServiceImpl.class);

  private PgPool pgPool;
  private JWTAuth provider;
  private PolicyService policyService;
  private TokenRevokeService revokeService;

  public TokenServiceImpl(PgPool pgPool, PolicyService policyService, JWTAuth provider,
      TokenRevokeService revokeService) {
    this.pgPool = pgPool;
    this.policyService = policyService;
    this.provider = provider;
    this.revokeService = revokeService;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public TokenService createToken(RequestToken requestToken, User user, Handler<AsyncResult<JsonObject>> handler) {
    LOGGER.debug(REQ_RECEIVED);

    String role = StringUtils.upperCase(requestToken.getRole());
    List<String> roles = user.getRoles().stream().map(r -> r.name()).collect(Collectors.toList());
    
    String itemType = requestToken.getItemType();
    JsonObject request = JsonObject.mapFrom(requestToken);
    request.put(USER_ID, user.getUserId());

    /* Checking if the userId is valid */
    if (user.getUserId().equals(NIL_UUID)) {
      Response r = new ResponseBuilder().status(404).type(URN_MISSING_INFO)
          .title(ERR_TITLE_NO_USER_PROFILE).detail(ERR_DETAIL_NO_USER_PROFILE).build();
      handler.handle(Future.succeededFuture(r.toJson()));
      return this;
    }
    
    /* Verify the user role */
    if (!roles.contains(role)) {
      LOGGER.error(LOG_UNAUTHORIZED + INVALID_ROLE);
      Response resp = new ResponseBuilder().status(400).type(URN_INVALID_ROLE).title(INVALID_ROLE)
          .detail(INVALID_ROLE).build();
      handler.handle(Future.succeededFuture(resp.toJson()));
      return this;
    }
    
    if (RESOURCE_SVR.equals(itemType)
        && (role.equalsIgnoreCase(ADMIN) || role.equalsIgnoreCase(CONSUMER))) {
      
      Tuple tuple = Tuple.of(requestToken.getItemId());
      pgSelelctQuery(GET_RS, tuple).onComplete(dbHandler -> {
        if (dbHandler.failed()) {
          LOGGER.error(LOG_DB_ERROR + dbHandler.cause());
          handler.handle(Future.failedFuture(INTERNAL_SVR_ERR));
          return;
        }

        if (dbHandler.succeeded() && dbHandler.result().size() > 0) {
          JsonObject dbExistsRow = dbHandler.result().getJsonObject(0);
          String flag = dbExistsRow.getString(OWNER);
          
          if (role.equalsIgnoreCase(ADMIN)) {
            if (!user.getUserId().equals(flag)) {
              LOGGER.error("Fail: " + ERR_ADMIN);
              Response resp = new ResponseBuilder().status(403).type(URN_INVALID_INPUT)
                  .title(ERR_ADMIN).detail(ERR_ADMIN).build();
              handler.handle(Future.succeededFuture(resp.toJson()));
              return;
            }
          }
          
          if(role.equalsIgnoreCase(CONSUMER)) {
            if(flag != null && flag.isEmpty()) {
              LOGGER.error("Fail: " + INVALID_RS_URL);
              Response resp = new ResponseBuilder().status(400).type(URN_INVALID_INPUT)
                  .title(INVALID_RS_URL).detail(INVALID_RS_URL).build();
              handler.handle(Future.succeededFuture(resp.toJson()));
              return; 
            }
          }
          
          request.put(URL, requestToken.getItemId());
          JsonObject jwt = getJwt(request);
          LOGGER.info(LOG_TOKEN_SUCC);
          Response resp = new ResponseBuilder().status(200).type(URN_SUCCESS).title(TOKEN_SUCCESS)
              .objectResults(jwt).build();
          handler.handle(Future.succeededFuture(resp.toJson()));
          return;
        } else {
          LOGGER.error("Fail: " + INVALID_RS_URL);
          Response resp = new ResponseBuilder().status(400).type(URN_INVALID_INPUT)
              .title(INVALID_RS_URL).detail(INVALID_RS_URL).build();
          handler.handle(Future.succeededFuture(resp.toJson()));
          return;
        }
      });
    } else {
      policyService.verifyPolicy(request, policyHandler -> {
        if (policyHandler.succeeded()) {

          request.mergeIn(policyHandler.result(), true);
          JsonObject jwt = getJwt(request);

          LOGGER.info(LOG_TOKEN_SUCC);
          Response resp = new ResponseBuilder().status(200).type(URN_SUCCESS).title(TOKEN_SUCCESS)
              .objectResults(jwt).build();
          handler.handle(Future.succeededFuture(resp.toJson()));

        } else if (policyHandler.failed()) {
          LOGGER.error("Fail: {}; {}", INVALID_POLICY, policyHandler.cause().getMessage());
          Response resp = new ResponseBuilder().status(403).type(URN_INVALID_INPUT)
              .title(INVALID_POLICY).detail(policyHandler.cause().getLocalizedMessage()).build();
          handler.handle(Future.succeededFuture(resp.toJson()));
        }
      });
    }
    
    return this;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public TokenService revokeToken(RevokeToken revokeToken, User user,
      Handler<AsyncResult<JsonObject>> handler) {

    LOGGER.debug(REQ_RECEIVED);
    
    /* Checking if the userId is valid */
    if (user.getUserId().equals(NIL_UUID)) {
      Response r = new ResponseBuilder().status(404).type(URN_MISSING_INFO)
          .title(ERR_TITLE_NO_USER_PROFILE).detail(ERR_DETAIL_NO_USER_PROFILE).build();
      handler.handle(Future.succeededFuture(r.toJson()));
      return this;
    }

    /* Verify the user has some roles */
    if (user.getRoles().isEmpty()) {
      Response resp = new ResponseBuilder().status(400).type(URN_INVALID_ROLE).title(INVALID_ROLE)
          .detail(INVALID_ROLE).build();
      handler.handle(Future.succeededFuture(resp.toJson()));
      return this;
    }

    String rsUrl = revokeToken.getRsUrl().toLowerCase();

    /* Check if the user is trying to revoke tokens on auth */
    if (rsUrl.equals(CLAIM_ISSUER)) {
      Response resp = new ResponseBuilder().status(400).type(URN_INVALID_INPUT)
          .title(CANNOT_REVOKE_ON_AUTH).detail(CANNOT_REVOKE_ON_AUTH).build();
      handler.handle(Future.succeededFuture(resp.toJson()));
      return this;
    }

    Tuple tuple = Tuple.of(rsUrl);

    pgSelelctQuery(GET_URL, tuple).onComplete(dbHandler -> {
      if (dbHandler.failed()) {
        LOGGER.error(LOG_DB_ERROR + dbHandler.cause());
        handler.handle(Future.failedFuture(INTERNAL_SVR_ERR));
        return;
      }

      if (dbHandler.succeeded()) {
        JsonObject dbExistsRow = dbHandler.result().getJsonObject(0);
        boolean flag = dbExistsRow.getBoolean(EXISTS);

        if (flag == Boolean.FALSE) {
          LOGGER.error("Fail: " + INVALID_RS_URL);
          Response resp = new ResponseBuilder().status(400).type(URN_INVALID_INPUT)
              .title(INVALID_RS_URL).detail(INVALID_RS_URL).build();
          handler.handle(Future.succeededFuture(resp.toJson()));
          return;
        }
        LOGGER.debug("Info: ResourceServer URL validated");
        
        JsonObject revokePayload =
            new JsonObject().put(USER_ID, user.getUserId()).put(RS_URL, revokeToken.getRsUrl());

        /* Here, we get the special admin token that is presented to other servers for token
         * revocation. The 'sub' field is the auth server domain instead of a UUID user ID.
         * The 'iss' field is the auth server domain as usual and 'aud' is the requested 
         * resource server domain. The rest of the field are not important, so they are
         * either null or blank. 
         */ 
        
        JsonObject adminTokenReq = new JsonObject().put(USER_ID, CLAIM_ISSUER).put(URL, rsUrl)
            .put(ROLE, "").put(ITEM_TYPE, "").put(ITEM_ID, "");
        String adminToken = getJwt(adminTokenReq).getString(ACCESS_TOKEN);
        
        revokeService.httpRevokeRequest(revokePayload, adminToken, result -> {
          if (result.succeeded()) {
            LOGGER.info(LOG_REVOKE_REQ);
            Response resp = new ResponseBuilder().status(200).type(URN_SUCCESS).title(TOKEN_REVOKED)
                .arrayResults(new JsonArray()).build();
            handler.handle(Future.succeededFuture(resp.toJson()));
            return;
          } else {
            LOGGER.error("Fail: {}; {}", FAILED_REVOKE, result.cause());
            Response resp = new ResponseBuilder().status(400).type(URN_INVALID_INPUT)
                .title(FAILED_REVOKE).detail(FAILED_REVOKE).build();
            handler.handle(Future.succeededFuture(resp.toJson()));
            return;
          }
        });
      }
    });

    return this;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public TokenService validateToken(IntrospectToken introspectToken,
      Handler<AsyncResult<JsonObject>> handler) {

    LOGGER.debug(REQ_RECEIVED);

    String accessToken = introspectToken.getAccessToken();
    if (accessToken == null || accessToken.isBlank()) {
      LOGGER.error(LOG_PARSE_TOKEN);
      Response resp = new ResponseBuilder().status(400).type(URN_MISSING_INFO)
          .title(MISSING_TOKEN).detail(MISSING_TOKEN).build();
      handler.handle(Future.succeededFuture(resp.toJson()));
      return this;
    }

    TokenCredentials authInfo = new TokenCredentials(accessToken);
    provider.authenticate(authInfo).onFailure(jwtError -> {
      LOGGER.error("Fail: {}; {}", TOKEN_FAILED, jwtError.getLocalizedMessage());
      Response resp =
          new ResponseBuilder().status(401).type(URN_INVALID_AUTH_TOKEN).title(TOKEN_FAILED)
              .arrayResults(new JsonArray().add(new JsonObject().put(STATUS, DENY))).build();
      handler.handle(Future.succeededFuture(resp.toJson()));
    }).onSuccess(jwtDetails -> {

      JsonObject accessTokenJwt = jwtDetails.attributes().getJsonObject(ACCESS_TOKEN);
      String userId = accessTokenJwt.getString(SUB);
      
      /* If the sub field is equal to the auth server domain, it is a
       * special admin token. Immediately send the decoded JWT back, as
       * there is no role/item information to be checked/validated.
       */
      if(userId.equals(CLAIM_ISSUER)) {
        Response resp = new ResponseBuilder().status(200).type(URN_SUCCESS)
            .title(TOKEN_AUTHENTICATED).objectResults(accessTokenJwt).build();
        handler.handle(Future.succeededFuture(resp.toJson()));
        return;
      }
      
      String role = accessTokenJwt.getString(ROLE);
      
      String[] item = accessTokenJwt.getString(IID).split(":");
      String itemId = item[1];
      String itemType = ITEM_TYPE_MAP.get(item[0]);

      Tuple tuple = Tuple.of(userId);
      pgSelelctQuery(CHECK_USER, tuple).onComplete(dbHandler -> {

        if (dbHandler.failed()) {
          LOGGER.error(LOG_DB_ERROR + dbHandler.cause().getMessage());
          handler.handle(Future.failedFuture(INTERNAL_SVR_ERR));
        } else if (dbHandler.succeeded()) {

        JsonObject dbExistsRow = dbHandler.result().getJsonObject(0);
        boolean flag = dbExistsRow.getBoolean(EXISTS);
        if (flag == Boolean.FALSE) {
            LOGGER.error(LOG_TOKEN_AUTH + INVALID_SUB);
            Response resp = new ResponseBuilder().status(400).type(URN_INVALID_AUTH_TOKEN)
                .title(TOKEN_FAILED).detail(INVALID_SUB).build();
            handler.handle(Future.succeededFuture(resp.toJson()));
            return;
          }
          
          if (RESOURCE_SVR.equals(itemType)) {
            Response resp = new ResponseBuilder().status(200).type(URN_SUCCESS)
                .title(TOKEN_AUTHENTICATED).objectResults(accessTokenJwt).build();
            LOGGER.info("Info: {}; {}", POLICY_SUCCESS, TOKEN_AUTHENTICATED);
            handler.handle(Future.succeededFuture(resp.toJson()));
            return;
          }

          JsonObject request = new JsonObject();
          request.put(USER_ID, userId)
                 .put(ROLE, role)
                 .put(ITEM_ID, itemId)
                 .put(ITEM_TYPE, itemType);

          policyService.verifyPolicy(request, policyHandler -> {
            if (policyHandler.succeeded()) {

              Response resp = new ResponseBuilder().status(200).type(URN_SUCCESS)
                  .title(TOKEN_AUTHENTICATED).objectResults(accessTokenJwt).build();
              LOGGER.info("Info: {}; {}", POLICY_SUCCESS, TOKEN_AUTHENTICATED);
              handler.handle(Future.succeededFuture(resp.toJson()));
              
            } else if (policyHandler.failed()) {
              LOGGER.error("Fail: {}; {}", INVALID_POLICY, policyHandler.cause().getMessage());
              Response resp = new ResponseBuilder().status(403).type(URN_INVALID_INPUT)
                  .title(INVALID_POLICY).detail(INVALID_POLICY).build();
              handler.handle(Future.succeededFuture(resp.toJson()));
            }
          });
        }
      });
    });
    return this;
  }
    
  /**
   * Generates the JWT token using the request data.
   * 
   * @param request
   * @return jwtToken
   */
  public JsonObject getJwt(JsonObject request) {
    
    JWTOptions options = new JWTOptions().setAlgorithm(JWT_ALGORITHM);
    long timestamp = System.currentTimeMillis() / 1000;
    long expiry = timestamp + CLAIM_EXPIRY;
    String itemType = request.getString(ITEM_TYPE);
    String iid = ITEM_TYPE_MAP.inverse().get(itemType)+":"+request.getString(ITEM_ID);
    String audience = request.getString(URL);
    
    /* Populate the token claims */
    JsonObject claims = new JsonObject();
    claims.put(SUB, request.getString(USER_ID))
          .put(ISS, CLAIM_ISSUER)
          .put(AUD, audience)
          .put(EXP, expiry)
          .put(IAT, timestamp)
          .put(IID, iid)
          .put(ROLE, request.getString(ROLE))
          .put(CONS, request.getJsonObject(CONSTRAINTS, new JsonObject()));
    
    String token = provider.generateToken(claims, options);

    JsonObject tokenResp = new JsonObject();
    tokenResp.put(ACCESS_TOKEN, token).put("expiry", expiry).put("server",
        audience);
    return tokenResp;
  }
  
  /**
   * Handles the PostgreSQL query.
   * 
   * @param query which is SQL
   * @param tuple which contains fields
   * @return future associated with Promise
   */
  Future<JsonArray> pgSelelctQuery(String query, Tuple tuple) {

    Promise<JsonArray> promise = Promise.promise();
    pgPool.withConnection(
        connection -> connection.preparedQuery(query).execute(tuple)).onComplete(handler -> {
          if (handler.succeeded()) {
            JsonArray jsonResult = new JsonArray();
            for (Row each : handler.result()) {
              jsonResult.add(each.toJson());
            }
            promise.complete(jsonResult);
          } else if (handler.failed()) {
            promise.fail(handler.cause());
          }
        });
    return promise.future();
  }
}
