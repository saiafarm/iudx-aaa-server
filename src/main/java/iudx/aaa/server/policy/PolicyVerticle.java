package iudx.aaa.server.policy;

import com.hazelcast.spi.impl.eventservice.impl.Registration;
import io.vertx.core.json.JsonObject;
import iudx.aaa.server.registration.RegistrationService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.eventbus.MessageConsumer;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgPool;
import io.vertx.serviceproxy.ServiceBinder;
import io.vertx.sqlclient.PoolOptions;

import static iudx.aaa.server.policy.Constants.REGISTRATION_SERVICE_ADDRESS;
import static iudx.aaa.server.token.Constants.POLICY_SERVICE_ADDRESS;
import java.util.Map;

/**
 * The Policy Verticle.
 * <h1>Policy Verticle</h1>
 * <p>
 * The Policy Verticle implementation in the the IUDX AAA Server exposes the
 * {@link iudx.aaa.server.policy.PolicyService} over the Vert.x Event Bus.
 * </p>
 *
 * @version 1.0
 * @since 2020-12-15
 */

public class PolicyVerticle extends AbstractVerticle {

  /* Database Properties */
  private String databaseIP;
  private int databasePort;
  private String databaseName;
  private String databaseSchema;
  private String databaseUserName;
  private String databasePassword;
  private int poolSize;
  private PgPool pgclient;
  private PoolOptions poolOptions;
  private PgConnectOptions connectOptions;
  private JsonObject catalogueOptions;
  private JsonObject authOptions;
  private JsonObject catOptions;
  private static final String POLICY_SERVICE_ADDRESS = "iudx.aaa.policy.service";
  private PolicyService policyService;
  private RegistrationService registrationService;
  private CatalogueClient catalogueClient;
  private ServiceBinder binder;
  private MessageConsumer<JsonObject> consumer;
  private static final Logger LOGGER = LogManager.getLogger(PolicyVerticle.class);

  /**
   * This method is used to start the Verticle. It deploys a verticle in a cluster, registers the
   * service with the Event bus against an address, publishes the service with the service discovery
   * interface.
   */

  @Override
  public void start() throws Exception {

    /* Read the configuration and set the postgres client properties. */
    LOGGER.debug("Info : " + LOGGER.getName() + " : Reading config file");

    databaseIP = config().getString("databaseIP");
    databasePort = Integer.parseInt(config().getString("databasePort"));
    databaseName = config().getString("databaseName");
    databaseSchema = config().getString("databaseSchema");
    databaseUserName = config().getString("databaseUserName");
    databasePassword = config().getString("databasePassword");
    poolSize = Integer.parseInt(config().getString("poolSize"));
    catalogueOptions = config().getJsonObject("catalogueOptions");
    catalogueOptions.put("domain",config().getString("domain"));
    catalogueOptions.put("resURL",config().getJsonObject("resOptions").getString("resURL"));
    authOptions = config().getJsonObject("authOptions");
    catOptions = config().getJsonObject("catOptions");

    /*
     * Injecting authServerUrl into 'authOptions' and 'catalogueOptions' from config().'authServerDomain'
     * TODO - make this uniform
     */
    authOptions.put("authServerUrl", config().getString("authServerDomain"));
    catalogueOptions.put("authServerUrl", config().getString("authServerDomain"));

    //get options for catalogue client

    /* Set Connection Object and schema */
    if (connectOptions == null) {
      Map<String, String> schemaProp = Map.of("search_path", databaseSchema);

      connectOptions = new PgConnectOptions().setPort(databasePort).setHost(databaseIP)
          .setDatabase(databaseName).setUser(databaseUserName).setPassword(databasePassword)
          .setProperties(schemaProp);
    }

    /* Pool options */
    if (poolOptions == null) {
      poolOptions = new PoolOptions().setMaxSize(poolSize);
    }

    /* Create the client pool */

    PgPool pool = PgPool.pool(vertx,connectOptions, poolOptions);
    registrationService = RegistrationService.createProxy(vertx, REGISTRATION_SERVICE_ADDRESS);
    catalogueClient = new CatalogueClient(vertx,pool,catalogueOptions);
    policyService = new PolicyServiceImpl(pool,registrationService,catalogueClient,authOptions,catOptions);

    binder = new ServiceBinder(vertx);
    consumer =
        binder.setAddress(POLICY_SERVICE_ADDRESS).register(PolicyService.class,
        policyService);

    LOGGER.debug("Info : " + LOGGER.getName() + " : Started");

  }

  @Override
  public void stop() {
    binder.unregister(consumer);
  }
}
