# qits-platform-maintenance

**The dependency inventory of every repository in the catalog**, the latest version of everything
they pin, and the maintenance branches that close the gap between the two.

It edits no file and pushes no branch. It reads manifests from the git host, asks the registries what
is newest, groups the pending upgrades per each repository's own configuration, and asks qits-ci to
apply one group as one branch. **This service decides *what* changes; a CI step applies them.**

It replaces the 71 per-repository `.config/qits/ci-event-upstream-*.yml` hop files: those followed
one internal release each, one branch per dependency, and force-pushed.

The contract — routes, model, config keys, schedules and the bump payload — is pinned by
`qits-maintenance-plan.md` in the qits-qits wrapper. Three repositories build against it.

## What it reads, and from where

| Fact | Peer | How |
|---|---|---|
| the catalog | qits-projects | `GET /projects/api/repositories`; a row with no `name` has no address and is skipped |
| manifests at `main` | qits-githost | `GET /git/<project>/<repo>/tree/<rev>[/<path>]` and `…/blob/<rev>/<path>` |
| internal latest | qits-artifacts | maven `maven-metadata.xml`, npm packument, OCI `/<name>/tags/list` |
| external latest | qits-platform-mirror | `central` maven-metadata, `npmjs` packument |
| applying a bump | qits-ci | `POST /ci/api/events/trigger`, event `MaintenanceBump` |
| the bump's outcome | qits-ci + qits-githost | `GET /ci/api/runs/{id}`, then the branch head |

**The head sha is resolved once per repository and every manifest is read at it.** The git host
stamps `Git-Commit-Sha` on every tree and blob answer, so one read of the root tree at `main` both
resolves the branch and proves the repository is readable. A scan that read each file at `main`
would be an inventory of whatever moved while it ran.

**A 404 from the git host is followed by a second read.** It answers "no such revision" and "no such
path" identically, so the root tree at the same sha is what tells ABSENT from GONE — qits-ci's
`HttpGitConfigSource` model, copied on purpose.

## What it scans

| Manifest | Ecosystem | Pins read | `location` |
|---|---|---|---|
| `pom.xml` + the poms its `<modules>` name | maven | `dependencies`, `dependencyManagement`, `parent` | `property:<name>`, `dependency:<g>:<a>`, `parent:<g>:<a>` |
| `package.json` + `package-lock.json` | npm | `dependencies`, `devDependencies` | `dependencies` / `devDependencies` |
| `Dockerfile`, `*.Dockerfile` | docker | every `FROM <image>:<tag>` | `line:<n>` |

`kind` says what can be done with a pin, which is not the same question as who published it:

| `kind` | meaning |
|---|---|
| `INTERNAL` / `EXTERNAL` | a real version, comparable and bumpable; the name rule decides which registry answers |
| `REACTOR` | **this repository's own artifact** — its version comes from maven's coordinates (`${project.version}`), or its `groupId:artifactId` is a module of this same reactor. It moves with this repository's release train and no line anywhere holds it. |
| `UNRESOLVED` | an expression this service could not resolve. Recorded so a person sees what the repository wrote. |

`REACTOR` and `UNRESOLVED` pins are shown, and are never looked up, never pending and never in a
bump payload.

Discovery is the repository ROOT plus the reactor, and nothing else. `service/src/main/webui` is a
gitlink to the SPA's own repository, which the catalog lists in its own right.

**The whole reactor is read before any of it is parsed**, because two of the rules below cannot be
answered from a single pom.

- **Expressions are resolved in the groupId and the artifactId too, not only the version** — from
  the pom's properties and from maven's own built-in coordinates (`${project.groupId}`,
  `${project.version}`, their `project.parent.*` siblings, the deprecated `pom.*` and the bare
  spellings), with a module that declares no groupId or version inheriting its parent's.
- **A property reference is resolved and remembered**, so the location is the property rather than
  the dependency element — rewriting the element would replace an expression with a literal. A pin
  whose property lives in the ROOT pom is recorded against the root pom, because that is the file
  holding the line. A BUILT-IN is never such a location: no file holds `${project.version}`.
- **A parent that is this repository's own root pom is not recorded at all.** It is the reactor's
  shape, not a dependency. A parent from OUTSIDE the reactor — a shared `qits-parent` from the
  registry — stays a pin and is bumpable.
- **A dependency with no version of its own is not a pin.** It takes one from a BOM; there is no
  line here to edit.
- **npm's version is the LOCK's, and the manifest's range rides beside it as `range`.** A range is
  not a version and cannot be compared with a registry's answer.
- **A digest or a tagless `FROM` is not a pin**, and v1 looks up only `qits/*` images: ordering base
  tags across vendors is a later decision.

## Grouping — `.config/qits/maintenance.yml`

```yaml
groups:
  - name: angular
    deps: ["@angular/*", "@qits/angular"]
  - name: quarkus
    deps: ["io.quarkus:*", "io.quarkus.platform:*"]
```

A group's name is also its branch: `maintenance/angular`. `deps` are globs — `*` and `?` only, over
the flat dependency name — and **a pin matching two groups belongs to the first declared**. Whatever
no configured group claims falls to `dependencies`, which is appended last and is also the whole
grouping of a repository that carries no file. **Invalid yaml is `CONFIG_ERROR` on the repository
row and nothing is bumped for it** — falling back to the default grouping would put changes on a
branch the author configured against.

## Pending

`mt_pin ⋈ mt_latest`, read through `mt_group`, computed on every read and never stored. A pin has to
be `INTERNAL` or `EXTERNAL` before anything is offered at all; then two rules decide:

1. it is strictly newer in that ecosystem's own order — maven's `ComparableVersion` for a pom, real
   semver for npm, the maven order over calver tags for an image;
2. **a prerelease is offered only when the pin is a prerelease too.**

`mt_latest.latest` is the highest **release**, falling back to a prerelease only when a dependency
has never published one. One column serves every pin of a dependency, and a release candidate
sitting in it would — by rule 2 — hide three stable upgrades from everyone.

A latest that could not be read offers nothing and says so: the pin carries `latestError`, because
"we could not find out" must not look like a green tick.

## The bump

`POST /repositories/{name}/groups/{group}/bumps` freezes the group's pending changes onto an
`mt_bump` row and queues it. The row id travels as the CI event's `eventId`, which is the dedupe key
— a dispatch whose answer was lost records no second run when it is retried.

```json
{ "name": "MaintenanceBump", "eventId": "<mt_bump.id>",
  "payload": { "repository": "qits-ci", "group": "dependencies",
               "branch": "maintenance/dependencies", "baseRef": "main",
               "changes": [ {"ecosystem":"maven","manifestPath":"pom.xml",
                             "name":"eu.wohlben.qits:qits-eventstream",
                             "from":"2026.811.1","to":"2026.821.3",
                             "location":"property:qits.eventstream.version"} ] } }
```

`location` is honoured for maven only; npm edits whichever section holds the entry and docker
anchors on the image name. It is sent anyway. `from` is never a precondition — a manifest already at
`to` is a quiet no-op. Every value is validated on this side against what the step enforces, so a
bad payload is a sentence on the bump row rather than a step log somebody has to read.

**Three answers from qits-ci and they mean different things:**

| answer | outcome |
|---|---|
| 200 with run ids | RUNNING; the poller follows them |
| **503** | RETRY. The bump stays REQUESTED with its changes and the sweep sends the same payload under the same event id. |
| **200 with no run id** | FAILED, `no run recorded for MaintenanceBump (repository unreadable or no platform pipeline)`. A run exists only if the repository was readable in that evaluation, so nothing is running and nothing will be. |

**The branch head is read twice — before the trigger and when the run ends — and only the head is
compared, never a commit count.** One bump is up to two commits, because the maven step and the
node/docker step each clone, commit and push.

| run | branch | bump | `mt_branch` |
|---|---|---|---|
| SUCCESS | moved | `SUCCEEDED` | `PUSHED` |
| SUCCESS | unmoved | `NOTHING_TO_DO` | unchanged |
| red | unmoved | `FAILED` | `FAILED` |
| red | **moved** | `FAILED` | `STALE` — the push is ff-only and never forced, so a branch that moved anyway is a person's commit. They own it now. |

## API

Under `/maintenance/api`. Every route takes `qits:admin` (a person, via qits-gateway's `X-Qits-User`
/ `X-Qits-Roles`) or `qits:system` (a machine, via a bearer). There is no anonymous route. Every
error body is `{"message": "..."}`.

```
GET  /repositories                                → [{name, project, lastScanAt, headSha, status,
                                                      message, pending,
                                                      groups:[{name, source, branch, state,
                                                               headSha, pending}]}]
GET  /repositories/{name}                         → the above, plus
                                                    pins:[{manifestPath, ecosystem, name, version,
                                                           range, kind, latest, latestError,
                                                           pending, group, location}]
GET  /dependencies?name=<glob>                    → [{ecosystem, name, latest, checkedAt, error,
                                                      pins:[{repository, version, manifestPath,
                                                             pending}]}]
POST /scans {scope, repository?}                  → 202 {id}    400 unknown scope
GET  /scans/{id}                                  → {id, scope, repository, trigger, status,
                                                     startedAt, finishedAt, message}
POST /repositories/{name}/groups/{group}/bumps    → 202 {id}    404 unknown repo or group
                                                                409 one is active, or bumping is off
GET  /bumps?repository=&limit=20                  → [the bump below]
GET  /bumps/{id}                                  → {id, repository, group, branch, environment,
                                                     trigger, status, ciEventId, ciRunId, ciRunIds,
                                                     configPath, ciRunStatus, startedAt, finishedAt,
                                                     message, changes:[…]}
```

- `scope` is `INTERNAL`, `EXTERNAL` or `ALL`. **Every scan re-reads every manifest whatever the
  scope says** — the scope governs only which half of the registry lookups refresh.
- **`POST` answers 202 and does not wait.** A scan is one git-host read per repository plus a
  registry lookup per dependency; a bump is a CI run. The client polls `GET /scans/{id}` or
  `GET /bumps/{id}`.
- A scan `FAILED` means the scan did nothing — the catalog was unreadable, or the run hit an
  exception. One unreachable repository is that repository's status, not the scan's.
- **A scan row is never left RUNNING.** Any exception closes it FAILED with the sentence, and at
  boot every scan a dead process left open is closed `interrupted by restart`: a scan's work is
  entirely in-process, so a successor cannot resume one and must not pretend it did. **Bumps are
  resumed instead** — their work is qits-ci's, the run outlived this service, and the first sweep
  after boot re-dispatches a REQUESTED bump under the same event id or polls a RUNNING one to its
  end.
- `GET /bumps` carries `changes` too; a change list is small.

The document is at `/maintenance/q/openapi`, the browsable UI at `/maintenance/q/swagger-ui`, and
readiness at `/maintenance/q/health/ready`. The client is served at `/maintenance/`.

## Configuration

Every key below is defaulted in the domain jar
(`maintenance/src/main/resources/META-INF/microprofile-config.properties`) and overridable by
environment without a rebuild.

| key | default | what it decides |
|---|---|---|
| `qits.maintenance.targets.projects-url` | `http://qits-projects:8080` | where the catalog is |
| `qits.maintenance.targets.githost-url` | `http://qits-githost:8080` | where the manifests are |
| `qits.maintenance.targets.ci-url` | `http://qits-ci:8080` | which CI applies a bump |
| `qits.maintenance.registries.maven-url` | `http://qits-artifacts:8080/artifacts/maven/maven` | internal maven |
| `qits.maintenance.registries.npm-url` | `http://qits-artifacts:8080/artifacts/npm/npm` | internal npm |
| `qits.maintenance.registries.oci-url` | `http://qits-artifacts:8080/v2` | internal images |
| `qits.maintenance.mirror.maven-url` | `http://qits-platform-mirror:8080/artifacts/maven/central` | Maven Central, cached |
| `qits.maintenance.mirror.npm-url` | `http://qits-platform-mirror:8080/artifacts/npm/npmjs` | npmjs, cached |
| `qits.maintenance.call-timeout` | `PT60S` | how long one peer call may take |
| `qits.maintenance.internal.maven-groups` | `eu.wohlben.qits` | which maven groups this platform publishes |
| `qits.maintenance.internal.npm-scopes` | `@qits` | which npm scopes it publishes |
| `qits.maintenance.internal.image-prefixes` | `qits/` | which images it publishes |
| `qits.maintenance.scan.enabled` | `true` | whether the CLOCK may scan |
| `qits.maintenance.scan.internal.cron` | `0 0 */6 * * ?` | the internal scan |
| `qits.maintenance.scan.external.cron` | `0 0 1 * * ?` | the external scan, 01:00 daily |
| `qits.maintenance.time-zone` | `UTC` | the zone both crons are read in |
| `qits.maintenance.bump.enabled` | `true` | whether a branch may be pushed at all |
| `qits.maintenance.bump.auto` | `true` | whether a SCHEDULED scan asks for the bumps it found |
| `qits.maintenance.bump.poll-interval` | `15s` | how often an unfinished bump is looked at |
| `qits.maintenance.environment` | `dev` | which environment's CI is recorded on a bump row |
| `qits.auth.machine.audience` | `qits-platform-maintenance` | this service's own id at qits-platform-idp |

**The registry keys carry a PATH as well as a host**, because a registry is mounted under a prefix
and the prefix names the repository row it serves. Moving a row is then a deployment's decision.

**`bump.enabled` stops the button as well as the schedule**, which is the point: a platform that
wants to watch what *would* change for a week reads the inventory and pushes nothing. `bump.auto`
only stops the schedule. **A manual scan never bumps under either setting** — pressing Scan asks
what is out of date, pressing Bump asks for a branch.

**One tier service by configured url.** qits-ci is per environment (`dev-qits-ci`) while this
service is platform tier, so a live platform injects the qualified name. Known debt, the same one
qits-configuration and qits-platform-orchestrator carry.

**Outbound credentials** are five named oidc clients — `projects`, `githost`, `ci`, `artifacts`,
`mirror` — all `client-id=qits-platform-maintenance`, all shipped `client-enabled=false`. A token is
cut for one service, which is why there are five; only the audience differs, and it is the one value
not defaulted, because it is environment-qualified. A deployment turns one on with

```
QUARKUS_OIDC_CLIENT_CI_CLIENT_ENABLED=true
QUARKUS_OIDC_CLIENT_CI_CREDENTIALS_SECRET=<this service's idp client secret>
QUARKUS_OIDC_CLIENT_CI_GRANT_OPTIONS_CLIENT_AUDIENCE=dev-qits-ci
```

Off, calls go out with the forward-auth pair alone (`X-Qits-User: qits-platform-maintenance`,
`X-Qits-Roles: qits:system`), which every call carries regardless.

**The store** is its own PostgreSQL database, `qits_platform_maintenance`, declared by
`resources: postgresql:db` in `.config/qits/deployments.yml`. Seven tables: `mt_repository`,
`mt_pin`, `mt_group`, `mt_latest` (an inventory a scan replaces wholesale) and `mt_scan`,
`mt_branch`, `mt_bump` (a log of what was asked and what came back, derivable from nothing).

## Rollout needs

**The idp client `qits-platform-maintenance` is the orchestrator's shape plus one claim.** The
claim is not optional — it is a route this service cannot use without it.

| what | why |
|---|---|
| roles `qits:system`, `qits-platform:system` | the same pair qits-platform-orchestrator's client carries. It covers qits-projects' catalog, qits-githost's content policy, qits-ci's trigger and — since qits-ci a3ecce2 — the read-only run and repository routes the bump poller follows. **`qits:admin` is NOT needed**: it is the human role, and this service is never a person. |
| claim `project` = `*` | qits-ci's trigger calls `machineAuth.requireProject("*")`, which passes only for a token literally granted every project. The bump names one repository but the trigger route demands them all. Today the only such grant is qits-platform-artifacts'; this service needs its own. |
| audiences `<env>-qits-ci`, `qits-projects`, `qits-githost` | a token is cut for one service. qits-githost ships `qits.auth.machine.required=true`, so its content reads need a real bearer addressed to it. |
| audiences `qits-platform-artifacts`, `qits-platform-mirror` | **not needed today** — the registry routes and the mirror's proxies are unguarded on qits-net. The two clients ship disabled for the day the edge's rule reaches the inside. |

In `qits-configuration` / `.qits-bootstrap.env` terms that is a client with
`_ROLES` carrying `qits:system,qits-platform:system`, `_CLAIMS_PROJECT: "*"`, and `_AUDIENCES`
listing the three services above.

**The wrapper needs `.config/qits/ci-platform-event-maintenance-bump.yml`** — the platform-level
pipeline that answers `MaintenanceBump` — and a qits-ci release carrying platform pipelines. Until
both exist every bump ends FAILED with `no run recorded for MaintenanceBump`, which is the honest
answer rather than a silent success.

## Building and testing

```
./mvnw clean verify -Dquarkus.http.test-port=0
```

Green on a clone with **no docker and no credentials** — the suite spawns its own PostgreSQL from a
Maven artifact (zonky) and the five peers are faked. It needs two things: a **node on PATH** and an
**initialised webui submodule**, because `verify` runs `package` and `package` is where Quinoa
builds the client. `./mvnw test` needs neither — Quinoa is off in test mode.

`-Dquarkus.http.test-port=0` is not optional on the deployment host: Quarkus' default test port 8081
is the platform's own npm registry there.

Integration tests are skipped by default. `-DskipITs=false` runs `PackagedSurfaceIT` against the
fast-jar; `-Dnative` builds the GraalVM binary (`.sdkmanrc` names `25.0.2-graalce`) and runs it
against that.

The image is `docker/Dockerfile`, built from the repo root with the client bundle already in the
context — see `AGENTS.md`.
