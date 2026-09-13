package eu.wohlben.qits.maintenance.idp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.util.Map;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;

/**
 * A deployment that has not declared {@code idp:client} yet — the OLD extras keys are set, the new
 * {@code QITS_RESOURCE_IDP_*} ones are not — resolves the {@code qits} client's id, secret and url
 * from them, byte for byte (service-client-identity-plan.md, C4). This is what keeps a deployment
 * running unchanged the moment this commit ships, before qits-deployments injects anything new.
 *
 * <p>The three peers this service could already reach live (`projects`, `githost`, `ci`) carried the
 * SAME client id and the SAME secret env value in every deployment (verified against
 * ComposeTemplate.java, 2026-09-13: all three read {@code QUARKUS_OIDC_CLIENT_*_CLIENT_ID} as the
 * derived alias and {@code QUARKUS_OIDC_CLIENT_*_CREDENTIALS_SECRET} as the one
 * {@code IDP_SECRET_PLATFORM_MAINTENANCE}), so one fallback pair is enough; this profile sets only
 * the `projects` client's old names, which is what the {@code qits} client's own keys read.
 */
@QuarkusTest
@TestProfile(QitsOidcClientOldExtrasFallbackTest.OldExtrasOnly.class)
class QitsOidcClientOldExtrasFallbackTest {

  public static class OldExtrasOnly implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      // Raw env names, not the dotted keys — a QuarkusTestProfile override is a config source in its
      // own right, so a property expression that names one of these resolves it exactly as a real
      // environment variable would.
      return Map.of(
          "QUARKUS_OIDC_CLIENT_PROJECTS_CLIENT_ID", "old-extras-qits-platform-maintenance",
          "QUARKUS_OIDC_CLIENT_PROJECTS_CREDENTIALS_SECRET", "old-extras-secret",
          "QUARKUS_OIDC_CLIENT_PROJECTS_AUTH_SERVER_URL", "http://old-extras-idp:8080/idp");
    }
  }

  private static String value(String key) {
    Config config = ConfigProvider.getConfig();
    return config.getValue(key, String.class);
  }

  @Test
  void theQitsClientFallsBackToTheOldExtrasEnvNames() {
    assertEquals(
        "old-extras-qits-platform-maintenance", value("quarkus.oidc-client.qits.client-id"));
    assertEquals("old-extras-secret", value("quarkus.oidc-client.qits.credentials.secret"));
    assertEquals(
        "http://old-extras-idp:8080/idp", value("quarkus.oidc-client.qits.auth-server-url"));
  }
}
