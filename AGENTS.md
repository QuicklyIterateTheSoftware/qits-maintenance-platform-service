# qits-platform-maintenance — working notes

Read `README.md` first: it defines the model, the parsers, the pending rule, the bump round trip,
the routes and the config keys. This file is the working conventions on top of it.

## The rules that shape everything

**This service DECIDES; a CI step APPLIES.** It clones nothing, edits no file and pushes no ref. A
bump is a payload naming a file, a location and two versions, and the step that reads it is the only
thing that touches a repository. Anything that would have this process write into somebody else's
tree goes in the pipeline instead.

**The contract is pinned by `qits-maintenance-plan.md` in the qits-qits wrapper.** The route shapes,
the model, the config keys and the bump payload are written down there and three repositories build
against them. Changing one of those shapes is a plan edit and a conversation, not a commit here.

**A clone of this repo alone builds and tests green** — no monorepo, no docker, no prior `mvn
install` elsewhere, no credentials. That is why the poms duplicate versions instead of inheriting
them, why the suite spawns its own PostgreSQL from a Maven artifact, and why the peers are faked at
the client rather than stubbed with a server.

**The one thing it needs besides Maven Central** is the platform's own Maven repository, for
`qits-db-core`, `qits-auth-core` and `qits-arch-rules`. `<repositories>` in the root pom points at
`${qits.maven.repository.url}`; the image build overrides it through `.qits-maven-settings.xml`,
which mirrors the exact repository id `qits-maven` — an exact id match is what gets past Maven's
`external:http:*` blocker.

**The gate is `./mvnw clean verify -Dquarkus.http.test-port=0`**, and it needs BOTH a node on PATH
and `git submodule update --init`. Always `clean` — incremental compilation leaves stale generated
classes behind when a shape changes. Port 0 is not optional on the deployment host: 8081 is the
platform's own npm registry there.

**Anything returned as `Response.entity(...)` is invisible to the build-time Jackson analysis**,
which is what `api/ApiWireReflection` exists for. The 202 from a queued scan and the 202 from a
requested bump are exactly such responses. A new response type joins that list in the commit that
adds it; the failure is a 500 in the native binary while every JVM test stays green.

## Reading another repository

**Resolve the head sha once, read every file at it.** `GitHostReader.head` reads the root tree at
`main` and takes `Git-Commit-Sha` off the answer, which resolves the branch AND proves the
repository is readable in one call. A scan that read manifests at `main` would produce an inventory
of whatever moved while it ran — pins that correspond to no commit that ever existed.

**A 404 is not an answer until it has been asked twice.** The git host spells "no such revision" and
"no such path" the same way, so a 404 on a file is followed by the root tree at the same sha:
answered means ABSENT, 404 means GONE. The five-outcome vocabulary is qits-ci's
`HttpGitConfigSource`; keep them matching.

**Discovery is the ROOT plus the reactor and nothing else.** A recursive walk would read every
vendored file in every repository on the platform for a handful of pins, and it would pull in
`service/src/main/webui` — a gitlink to an SPA's own repository, which the catalog lists in its own
right. The module walk refuses any path containing `src/main/webui` outright.

**Nothing over the wire throws.** A scan reads every repository in the catalog; one unreachable git
host must cost one row's status, not the run. Every failure comes back as a `PeerAnswer` carrying
the sentence.

**An unreachable repository KEEPS ITS PINS.** `markRepository` writes the status and leaves the
inventory alone — a peer that could not be asked is not evidence that a repository stopped pinning
anything, and wiping on every hiccup would make "pending" flicker to zero whenever the git host
restarted.

## Parsers

**XML-aware, never a regular expression.** A pom is read to find WHERE a version is set, and the
step edits by that location — so a wrong location is a wrong edit in somebody else's repository. A
line-based reader cannot tell a `<version>` inside `dependencyManagement` from one inside a plugin,
or a commented-out block from a live one. `manifest/Xml` is the DOM, with external entities and the
DOCTYPE off: every document comes off another service over HTTP.

**A property is remembered, not merely expanded**, and a property defined in the ROOT pom makes the
pin belong to the ROOT pom. Recording the module would send the step to a file that does not hold
the value.

**Only what has a line is a pin.** A maven dependency with no version takes one from a BOM; an npm
dependency the lock does not resolve is a lock out of step with its manifest; a `FROM` with a digest
or no tag has no order. None of them is recorded — an inventory entry nothing can bump is noise on
every page it appears on.

**Adding an ecosystem is a parser, a resolver and a pipeline step together.** Two of the three is a
column that fills up and never moves.

## Versions

**Three orders, because three ecosystems disagree.** Maven's is `ComparableVersion` — the class a
resolver ranks with, so this service and a build agree. npm's is real semver, which ranks
prereleases by rules maven does not share and disagrees outright on `1.0.0-1` against
`1.0.0-alpha`. An OCI tag is read as a version when it starts with digits and ignored when it does
not: `latest` is a moving reference and `jdk-25` is an upstream's naming.

**`mt_latest.latest` is the highest RELEASE**, falling back to a prerelease only when a dependency
has never published one. One column serves every pin of that dependency, so a release candidate
sitting in it would — by the pending rule — hide every stable upgrade from everyone with a released
pin. The fallback is what keeps SNAPSHOT-only artifacts visible, which is the case the pending
rule's "unless the pin is one too" is written for.

## The worker

**ONE THREAD for the whole service**, scans and bumps alike. A scan rewrites the inventory a bump
reads to compose its payload; two at once would let a bump send a payload computed from a repository
half-rewritten. It also means the git host and the registries see one caller.

**A second bump of one (repository, group) is refused earlier still**, by `MaintenanceStore.openBump`,
whose active-bump check is **inside** the opening transaction — a person and a scheduled scan
arriving together is the ordinary case, not a race worth losing.

**A task never throws out of `WorkQueue`.** A thrown exception would lose the sentence; every task
logs its own failure and ends.

**`awaitIdle` is a barrier task, not a queue-length check.** The executor is single-threaded, so
work that reaches the front after the barrier does not exist yet.

## Bumping

**The changes are frozen at REQUEST time**, not recomputed at dispatch. A payload recomputed later
would not be the one the operator saw, and a retry after a 503 would send a different list under the
same dedupe key.

**The event id IS the bump row id**, and qits-ci dedupes on (event id, repository, config path). A
dispatch whose answer was lost records no second run when it is retried. Never generate a fresh one.

**Three answers, three meanings.** 503 is RETRY and the bump stays REQUESTED with its changes; a 200
with no run id is FAILED, because qits-ci records a run only if the payload's repository was
readable in that evaluation; anything else non-2xx is FAILED. Treating an empty `runIds` as success
would report a branch that was never written.

**Only the branch HEAD is compared, never a commit count.** One bump is up to two commits — the
maven step and the node/docker step each clone, commit and push — so a service expecting one would
report every mixed group as broken. The head is read before the trigger and again when the run ends;
unmoved after a green run is NOTHING_TO_DO, and moved after a red one is STALE.

**`BumpPayload.problems` refuses on this side what the step refuses on that one.** The step holds
`to`, `group`, the refs and the manifest path to the same rules; failing here puts the reason on the
bump row instead of in a step log somebody has to go and read.

## Persistence

`MaintenanceStore` is the only writer. **Every write is a `DbRetry.inNewTx` ending in a flush**:
`inNewTx` owns the transaction boundary, which is the only way a retry can tell "the body threw, so
it certainly never committed" from "the transaction manager reported it" — Narayana spells a lost
commit and a real rollback with the same exception.

**Every method is `@ActivateRequestContext`**, because the caller is usually the worker thread and a
Hibernate session is bound to that context. A route's call already has one, so the annotation covers
both callers.

**Set every field BEFORE `persist`.** `replaceInventory` runs a delete query right after, and
Hibernate flushes before a query — a row persisted with its not-null columns still unset fails the
flush rather than the insert, naming a column nobody was writing at the time. Measured, not
theoretical.

**One repository's inventory is replaced in ONE transaction.** A scan that committed the delete and
failed the insert would leave a repository looking as though it pins nothing — which is also what
"nothing pending" looks like.

Schema changes go in `maintenance/src/main/resources/db/maintenance/migration/`, hand-written, its
own lineage on its own datasource. Keep appending, never edit an applied migration.

## Identity: two tracks, one set of roles

A request with no `Authorization` header is USER traffic — qits-gateway performed the login and
asserted `X-Qits-User` / `X-Qits-Roles`. A request WITH a bearer is MACHINE traffic, validated by
quarkus-oidc against qits-platform-idp.

**Both land as roles, which is why every route is `@RolesAllowed({"qits:admin", "qits:system"})`.**
An operator presses Bump in a browser; a machine may post the same request. There is no anonymous
route here and there must never be one — the write surface pushes branches into every repository on
the platform.

**Outbound, this service carries BOTH roles**, and that is not tidiness: `GET /ci/api/runs/{id}` is
`@RolesAllowed("qits:admin")` while qits-ci's trigger and every git-host read take `qits:system`. A
system-only identity would leave every bump RUNNING for ever. See README's "Rollout needs" — the
same asymmetry is what the idp client has to carry.

`quarkus.oidc.tenant-enabled=${qits.auth.machine.required:false}` — validation follows the rollout
gate rather than standing on its own, so with the gate off there is no OIDC tenant, nothing fetches
a JWKS, and a clone-alone build needs no issuer. There is no third state.

## Tests

- App-level config lives in `service/src/main/resources/application.properties` and **the tests
  inherit it** — Quarkus merges the test resources over it rather than replacing it. Never
  re-declare an app-level setting in test resources.
- **No dev services and no containers, ever.** `EmbeddedPg` starts zonky's postgres and
  `EmbeddedPgConfigSource` hands its coordinates to every `@QuarkusTest` at an ordinal above
  `application.properties`, because the port is chosen at run time. Both are **copied** per module
  rather than shared: a test-jar dependency between two modules that have none is the higher price.
  Each module names its own database.
- **`FakePeers` is an `@Alternative` over `PeerClient.get` / `.post`** — replacing those two is
  replacing the network, at no port and no dependency, and the urls in the assertions stay the real
  ones because the inherited `url()` still resolves them from the shipped target configuration. It
  keys on the TARGET as well as the path: three of the eight targets are one service behind three
  prefixes, and a path alone would let a maven lookup answer for the mirror's.
- **An unscripted path answers 404**, because that is what the git host says about a file a
  repository does not carry — the ordinary case in a scan, and it keeps a fixture to what it means
  to say.
- **Every poll is a fresh HTTP request, and that is load-bearing.** A `@QuarkusTest` holds ONE
  request context for the whole method, so one Hibernate session would answer every read from its
  first-level cache — the row would look unchanged for ever while the worker closed it in another
  session.
- **`InventoryReset` empties the store between methods, after `WorkQueue.awaitIdle`.** Flyway's
  `clean-at-start` runs per Quarkus start, not per test, and an active bump row holds its branch's
  lock: without the reset the second test of a class is answered 409 by the first test's leftovers.
  The drain first, or the delete lands between a running task's read and its write.
- **The scheduler is off in the suite** (`quarkus.scheduler.enabled=false`). The crons would fire
  once a day for nobody's reason; the bump sweep is an INTERVAL and would fire, racing every test
  that drives a bump by hand.
- A `@QuarkusTest` runs under the `test` profile, where qits-auth-core ships a dev user carrying
  `qits:admin` and `qits:system` — so the shipped `@RolesAllowed` pair is exercised rather than
  bypassed, with no `@TestSecurity` fabricating an identity no deployment produces.
- **`PackagedSurfaceIT` is the only test that runs against the artifact**, and the only place the
  identity contract is real. It hands the process `QITS_RESOURCE_DB_*` rather than restating the
  datasource keys, so the jar's own indirection is under test, and it reads the rows back over JDBC.
  **Its peers are real calls to a dead loopback port** — the honest end-to-end proof that a failure
  reaches a readable row with none of the suite's fakes involved. ITs are skipped by default;
  `-DskipITs=false` runs against the fast-jar and `-Dnative` against the binary.

## The client

`service/src/main/webui` is the `qits-platform-spa-maintenance` submodule (`ignore = all`,
`update = merge`, `branch = main` — the sibling shape). Quinoa 2.8.2 is pinned by hand in the root
pom, because Quinoa is in no BOM and its version does not track the platform's.

- **The segment is spelled twice**, `quarkus.quinoa.ui-root-path` here and `baseHref` in the
  submodule's `angular.json`. A mismatch serves a page whose every asset 404s and nothing on this
  side notices, so `PackagedSurfaceIT` asserts the `<base href>` string rather than the status.
- **`ignored-path-prefixes` values are RELATIVE**, matched after `ui-root-path` is stripped: `/api`
  and `/q`, never `/maintenance/api`. An absolute value matches nothing and is indistinguishable
  from an unset key. Setting the key REPLACES Quinoa's derivation, which is why both are spelled by
  hand. **Add a literal route under `/maintenance` and its entry here in the same commit** — and
  give it a segment of its own, because an entry protects a segment and not a string prefix.
- **The bundle is built OUTSIDE the docker build.** `@qits/ui-components` exists only on the
  platform's own npm registry, which a `RUN` reaches by no address at all. So
  `.config/qits/ci-post-receive.yml` builds it in the step container (on qits-net) and the
  Dockerfile neuters Quinoa's install/ci/build commands with `--version`, guards the staged bundle
  with a `test -f` before the multi-minute native compile, and `cp`s the bundle onto itself so
  Quinoa's MOVE does not hit overlayfs' EXDEV.
- **Quinoa is off in test mode and stays off.** Every claim about the SPA belongs in
  `PackagedSurfaceIT`.
- **`quarkus-undertow` must never be on the classpath.** It arrives transitively from anything
  servlet-shaped and takes over the static-resource route Quinoa serves the bundle through — a
  packaged process that answers the API correctly and the SPA with a 404.

      ./mvnw -pl service -am dependency:tree | grep -i undertow

## Deliberately not here yet

Each is a decision, not an omission:

- **A `SoftwareRelease` listener.** Polling only in v1 — the bus listener that turns an internal
  release into "pending" within seconds is the second release, and it arrives with the eventstream
  vocabulary jar every announcing service has.
- **Transitive dependencies.** A manifest holds direct pins and only those have a line to edit.
- **External base image tags.** `FROM eclipse-temurin:…` — ordering tags across vendors is a later
  decision, and the mirror would answer with an upstream's whole tag history with no rule to rank
  it by. `FROM qits/*` is in scope.
- **`ng update` and tool-driven upgrades.** A group carries `name` and `deps`, and the step edits
  lines. A migration is a person's job.
- **Workspace creation.** Pushing the branch is the whole "merge request"; it is released through
  the workspaces door like any other branch.
- **Cancelling a bump.** There is no route and no column. The CI run has its own cancel, and a bump
  row saying CANCELLED would be a claim this service cannot make about a step that may already have
  pushed.
- **A second environment.** Every bump row records the environment it ran in, so a second one is a
  config entry — but the routing that would pick between two CIs is not written.
