package eu.wohlben.qits.maintenance.idp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import java.util.Optional;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;

/**
 * The one named oidc client, {@code qits}, as the shipped configuration resolves it with no {@code
 * QITS_RESOURCE_IDP_*} or old extras env set — the "nothing configured" arm every clone-alone build
 * and every other test in this repo runs on (service-client-identity-plan.md, C4).
 *
 * <p>{@link QitsOidcClientOldExtrasFallbackTest} and {@link
 * QitsOidcClientResourceOverridesOldExtrasTest} hold the other two arms — the old extras keys alone,
 * and the new resource keys winning over them — each in its own {@code @QuarkusTest} because a
 * {@code @TestProfile}'s config overrides are fixed for the life of one boot.
 */
@QuarkusTest
class QitsOidcClientShippedConfigTest {

  private static String value(String key) {
    Config config = ConfigProvider.getConfig();
    return config.getValue(key, String.class);
  }

  @Test
  void theQitsClientResolvesItsOwnLiteralDefaults() {
    assertEquals("http://qits-platform-idp:8080/idp", value("quarkus.oidc-client.qits.auth-server-url"));
    assertEquals("qits-platform-maintenance", value("quarkus.oidc-client.qits.client-id"));
    // Empty, not absent — SmallRye reads a configured-empty String as null, so an empty secret reads
    // as an empty Optional rather than as "" itself.
    Optional<String> secret =
        ConfigProvider.getConfig()
            .getOptionalValue("quarkus.oidc-client.qits.credentials.secret", String.class);
    assertTrue(secret.isEmpty());
    // One audience for every peer now, never a peer-scoped one.
    assertEquals("qits-platform", value("quarkus.oidc-client.qits.grant-options.client.audience"));
  }

  @Test
  void theClientStaysDisabledUnderTest() {
    // %test.quarkus.oidc-client.qits.client-enabled=false wins over the shipped expression
    // regardless of what QUARKUS_OIDC_CLIENT_PROJECTS_CLIENT_ENABLED says — the arm every test in
    // this repo is on, so a suite never dials a real idp.
    assertEquals("false", value("quarkus.oidc-client.qits.client-enabled"));
  }

  @Test
  void theOldFiveClientsStayDisabledAndInert() {
    for (String name : new String[] {"projects", "githost", "ci", "artifacts", "mirror"}) {
      assertEquals(
          "false", value("quarkus.oidc-client." + name + ".client-enabled"), name + " client-enabled");
      assertEquals(
          "false",
          value("quarkus.oidc-client." + name + ".early-tokens-acquisition"),
          name + " early-tokens-acquisition");
    }
    assertEquals("false", value("quarkus.oidc-client.client-enabled"));
  }
}
