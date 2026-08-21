package eu.wohlben.qits.maintenance.api;

import eu.wohlben.qits.maintenance.peer.FakePeers;
import eu.wohlben.qits.maintenance.peer.PeerTarget;
import java.util.Map;

/**
 * One repository as five peers would describe it.
 *
 * <p>It is deliberately a WHOLE repository rather than a minimal one: a pom with a property and a
 * literal, a package.json with a lock, a Dockerfile with an internal and an external image, and a
 * grouping file. The point of the suite is the seams between those, and a fixture that carried one
 * pin would exercise none of them.
 */
final class Fixture {

  static final String PROJECT = "qits";
  static final String REPOSITORY = "qits-ci";
  static final String HEAD_SHA = "3f1a9c0b7d2e4f5a6b8c9d0e1f2a3b4c5d6e7f80";
  static final String BRANCH = "maintenance/dependencies";
  static final String BUMPED_SHA = "aa11bb22cc33dd44ee55ff6677889900aabbccdd";

  private static final String TREE = "/git/" + PROJECT + "/" + REPOSITORY + "/tree/";
  private static final String BLOB = "/git/" + PROJECT + "/" + REPOSITORY + "/blob/" + HEAD_SHA + "/";

  private static final String POM =
      """
      <project>
        <groupId>eu.wohlben.qits</groupId>
        <artifactId>qits-ci</artifactId>
        <version>2026.821.1</version>
        <properties>
          <qits.eventstream.version>2026.811.1</qits.eventstream.version>
        </properties>
        <dependencies>
          <dependency>
            <groupId>eu.wohlben.qits</groupId>
            <artifactId>qits-eventstream</artifactId>
            <version>${qits.eventstream.version}</version>
          </dependency>
          <dependency>
            <groupId>io.quarkus.platform</groupId>
            <artifactId>quarkus-bom</artifactId>
            <version>3.34.5</version>
          </dependency>
        </dependencies>
      </project>
      """;

  private static final String PACKAGE_JSON =
      """
      {"name":"client",
       "dependencies":{"@qits/ui-components":"2026.8.1","@angular/core":"^21.0.0"}}
      """;

  private static final String PACKAGE_LOCK =
      """
      {"lockfileVersion":3,
       "packages":{"":{"name":"client"},
                   "node_modules/@qits/ui-components":{"version":"2026.8.1"},
                   "node_modules/@angular/core":{"version":"21.0.4"}}}
      """;

  private static final String DOCKERFILE =
      """
      FROM mirror.dev.localhost:8080/quay/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25 AS build
      FROM qits/build-images/maven-base:2026.813.1
      """;

  private static final String MAINTENANCE_YML =
      """
      groups:
        - name: angular
          deps: ["@angular/*"]
      """;

  private Fixture() {}

  /** Everything a scan of one repository reads, answered. */
  static void scriptScan(FakePeers peers) {
    peers.answer(
        PeerTarget.PROJECTS,
        "/projects/api/repositories",
        FakePeers.Scripted.ok(
            "{\"repositories\":[{\"id\":\"r1\",\"projectId\":\""
                + PROJECT
                + "\",\"name\":\""
                + REPOSITORY
                + "\",\"mainBranch\":\"main\"},"
                // A row with no alias has no address, so a scan must skip it rather than fail on it.
                + "{\"id\":\"r2\",\"projectId\":\"qits\",\"name\":null,\"mainBranch\":\"main\"}]}"));

    String root =
        "{\"entries\":["
            + "{\"name\":\"pom.xml\",\"type\":\"blob\"},"
            + "{\"name\":\"package.json\",\"type\":\"blob\"},"
            + "{\"name\":\"package-lock.json\",\"type\":\"blob\"},"
            + "{\"name\":\"Dockerfile\",\"type\":\"blob\"},"
            + "{\"name\":\"service\",\"type\":\"tree\"}]}";
    Map<String, String> sha = Map.of("Git-Commit-Sha", HEAD_SHA);
    peers.answer(PeerTarget.GITHOST, TREE + "main", FakePeers.Scripted.ok(root, sha));
    // The same listing at the sha: it is what a 404 on a blob is checked against, and answering it
    // is the difference between ABSENT and GONE.
    peers.answer(PeerTarget.GITHOST, TREE + HEAD_SHA, FakePeers.Scripted.ok(root, sha));

    peers.answer(PeerTarget.GITHOST, BLOB + "pom.xml", FakePeers.Scripted.ok(POM, sha));
    peers.answer(PeerTarget.GITHOST, BLOB + "package.json", FakePeers.Scripted.ok(PACKAGE_JSON, sha));
    peers.answer(
        PeerTarget.GITHOST, BLOB + "package-lock.json", FakePeers.Scripted.ok(PACKAGE_LOCK, sha));
    peers.answer(PeerTarget.GITHOST, BLOB + "Dockerfile", FakePeers.Scripted.ok(DOCKERFILE, sha));
    peers.answer(
        PeerTarget.GITHOST,
        BLOB + ".config/qits/maintenance.yml",
        FakePeers.Scripted.ok(MAINTENANCE_YML, sha));

    // The registries. Internal names go to qits-artifacts, external ones through the mirror.
    peers.answer(
        PeerTarget.MAVEN_REGISTRY,
        "/eu/wohlben/qits/qits-eventstream/maven-metadata.xml",
        FakePeers.Scripted.ok(metadata("2026.811.1", "2026.821.3", "2026.900.1-SNAPSHOT")));
    peers.answer(
        PeerTarget.MAVEN_MIRROR,
        "/io/quarkus/platform/quarkus-bom/maven-metadata.xml",
        FakePeers.Scripted.ok(metadata("3.34.5", "3.34.6", "3.35.0.CR1")));
    peers.answer(
        PeerTarget.NPM_REGISTRY,
        "/@qits%2fui-components",
        FakePeers.Scripted.ok(packument("2026.8.1", "2026.8.4")));
    peers.answer(
        PeerTarget.NPM_MIRROR,
        "/@angular%2fcore",
        FakePeers.Scripted.ok(packument("21.0.4", "21.1.0")));
    peers.answer(
        PeerTarget.OCI_REGISTRY,
        "/qits/build-images/maven-base/tags/list?n=1000",
        FakePeers.Scripted.ok(
            "{\"name\":\"qits/build-images/maven-base\",\"tags\":"
                + "[\"latest\",\"2026.813.1\",\"2026.821.2\"]}"));
  }

  /** The branch does not exist yet — the ordinary state before a first bump. */
  static void scriptBranchAbsent(FakePeers peers) {
    peers.answer(PeerTarget.GITHOST, TREE + "maintenance%2Fdependencies", FakePeers.Scripted.status(404, ""));
  }

  /** The branch exists at that sha — what the git host says after a bump ran. */
  static void scriptBranchAt(FakePeers peers, String sha) {
    peers.answer(
        PeerTarget.GITHOST,
        TREE + "maintenance%2Fdependencies",
        FakePeers.Scripted.ok("{\"entries\":[]}", Map.of("Git-Commit-Sha", sha)));
  }

  /** qits-ci accepts the trigger and names one run. */
  static void scriptCiAccepts(FakePeers peers, String runId) {
    peers.answer(
        PeerTarget.CI,
        "/ci/api/events/trigger",
        FakePeers.Scripted.ok(
            "{\"eventId\":\"e1\",\"runIds\":[\"" + runId + "\"],\"repositoriesRead\":1,"
                + "\"repositoriesSkipped\":[]}"));
  }

  static void scriptRun(FakePeers peers, String runId, String status) {
    peers.answer(
        PeerTarget.CI,
        "/ci/api/runs/" + runId,
        FakePeers.Scripted.ok("{\"id\":\"" + runId + "\",\"status\":\"" + status + "\"}"));
  }

  private static String metadata(String... versions) {
    StringBuilder xml =
        new StringBuilder("<metadata><versioning><versions>");
    for (String version : versions) {
      xml.append("<version>").append(version).append("</version>");
    }
    return xml.append("</versions></versioning></metadata>").toString();
  }

  private static String packument(String... versions) {
    StringBuilder json = new StringBuilder("{\"versions\":{");
    for (int i = 0; i < versions.length; i++) {
      if (i > 0) {
        json.append(',');
      }
      json.append('"').append(versions[i]).append("\":{}");
    }
    return json.append("},\"dist-tags\":{\"latest\":\"")
        .append(versions[versions.length - 1])
        .append("\"}}")
        .toString();
  }
}
