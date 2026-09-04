# qits-platform-maintenance

**The dependency inventory of every repository in the catalog**, the latest version of everything
they pin, and the maintenance branches that close the gap between the two.

It edits no file and pushes no branch. It reads manifests from the git host, asks the registries what
is newest, groups the pending upgrades per each repository's own configuration, and asks qits-ci to
apply one group as one branch. **This service decides *what* changes; a CI step applies them.**

It replaces the 71 per-repository `.config/qits/ci-event-upstream-*.yml` hop files: those followed
one internal release each, one branch per dependency, and force-pushed. The fifteen
`ci-event-upstream-frontend.yml` files are the same story one layer down — a service following its
own SPA submodule — and they are what the `gitlink` ecosystem retires.

The contract — routes, model, config keys, schedules and the bump payload — is pinned by
`qits-maintenance-plan.md` in the qits-qits wrapper. Three repositories build against it.

## What it reads, and from where

| Fact | Peer | How |
|---|---|---|
| the catalog | qits-projects | `GET /projects/api/repositories`; a row with no `name` has no address and is skipped. The row's `id` is kept as `mt_repository.catalog_id` — never an address here, and the only way another context's spelling of a repository is read back as a name. **The listing is authoritative in both directions**: what it stops naming goes ABSENT — see below |
| manifests at `main` | qits-githost | `GET /git/<project>/<repo>/tree/<rev>[/<path>]` and `…/blob/<rev>/<path>` |
| internal latest | qits-artifacts | maven `maven-metadata.xml`, npm packument, OCI `/<name>/tags/list` |
| external latest | qits-platform-mirror | `central` maven-metadata, `npmjs` packument |
| applying a bump | qits-ci | `POST /ci/api/events/trigger`, event `MaintenanceBump` |
| the bump's outcome | qits-ci + qits-githost | `GET /ci/api/runs/{id}`, then the branch head |
| an internal release | qits-events | `SoftwareRelease` off the durable bus — see **The event bus** |
| a branch's life | qits-events | `SCMRelease`, `SCMDeleteBranch`, `SCMPublishCommit` |
| what a release CONTAINS | qits-artifacts | `GET /artifacts/sboms/<type>/<name>/-/<version>` — one CycloneDX document per released artifact; see **The dependency graph** |

**The head sha is resolved once per repository and every manifest is read at it.** The git host
stamps `Git-Commit-Sha` on every tree and blob answer, so one read of the root tree at `main` both
resolves the branch and proves the repository is readable. A scan that read each file at `main`
would be an inventory of whatever moved while it ran.

**A 404 from the git host is followed by a second read.** It answers "no such revision" and "no such
path" identically, so the root tree at the same sha is what tells ABSENT from GONE — qits-ci's
`HttpGitConfigSource` model, copied on purpose.

**THE INVENTORY FOLLOWS THE CATALOG OUT, NOT ONLY IN.** A scan that read the whole catalog marks
every `mt_repository` row the listing does not name `ABSENT` with `dropped from the catalog`, and
deletes its `mt_pin` and `mt_group` rows. Until 2026-09-03 a scan only ever upserted what the catalog
listed, so a renamed or removed repository kept the status its last successful scan wrote — OK — with
its pins, its groups and its pending count, for ever, because nothing else writes those rows.
**Measured on the first live nightly bump, 2026-09-03**: 48 repositories in the catalog against 96
rows here and ~800 pending changes; the clock asked for 30 bumps and 23 came back FAILED with `no run
recorded for MaintenanceBump`, every one of them a pre-rename ghost (`qits-spa-artifacts`,
`qits-stt`, `qits-projects`, `qits-platform-spa-*`).

- **The row is KEPT, not deleted.** The name still answers on `GET /repositories/{name}` with an
  honest status instead of a 404 that says nothing about why, and `catalog_id` — the translation
  every `mt_artifact` row written under another context's spelling reads back through — survives
  with it. `mt_branch`, `mt_scan` and `mt_bump` stay too: those are the LOG of what was asked and
  what came back, and a repository leaving the catalog does not un-push a branch that was pushed.
- **The pins and the groups go**, because they are a cache of the catalog's world. A repository the
  catalog dropped contributes no pending change and offers the clock no group; `BumpSchedule` skips
  everything that is not OK, and the button 404s on a repository that declares no group.
- **Three things never reconcile, and each of them would mark the whole platform absent.** A scan of
  ONE repository (`POST /scans {repository}`, and every `ScanTrigger.EVENT` push) read a listing of
  one and is evidence about nothing else; a catalog read that FAILED carries an empty list and is one
  peer's outage; and a read that succeeded and listed NOTHING closes the scan FAILED before the
  reconciliation is reached. The last guard is spelled twice, in `ScanService` and again in
  `MaintenanceStore.reconcileCatalog`, because the cost of the two disagreeing is the whole store.
- **A repository that RETURNS needs nothing of its own**: the next scan lists it and the ordinary
  upsert writes OK over the ABSENT row with fresh pins and fresh groups.

## What it scans

| Manifest | Ecosystem | Pins read | `location` |
|---|---|---|---|
| `pom.xml` + the poms its `<modules>` name | maven | `dependencies`, `dependencyManagement`, `parent` | `property:<name>`, `dependency:<g>:<a>`, `parent:<g>:<a>` |
| `package.json` + `package-lock.json` | npm | `dependencies`, `devDependencies` | `dependencies` / `devDependencies` |
| `Dockerfile`, `*.Dockerfile` | docker | every `FROM <image>:<tag>` | `line:<n>` |
| `.gitmodules` + the tree's `160000` entries | gitlink | every submodule | `gitlink:<path>` |

`kind` says what can be done with a pin, which is not the same question as who published it:

| `kind` | meaning |
|---|---|
| `INTERNAL` / `EXTERNAL` | a real version, comparable and bumpable; the name rule decides which registry answers |
| `REACTOR` | **this repository's own artifact** — its version comes from maven's coordinates (`${project.version}`), or its `groupId:artifactId` is a module of this same reactor. It moves with this repository's release train and no line anywhere holds it. |
| `UNRESOLVED` | an expression this service could not resolve. Recorded so a person sees what the repository wrote. |

`REACTOR` and `UNRESOLVED` pins are shown, and are never looked up, never pending and never in a
bump payload. **A gitlink is always `INTERNAL`, by construction rather than by a name rule** — a
submodule is a repository on this platform's own git host and nothing else can be one, so there is
no key to configure and no external half.

Discovery is the repository ROOT plus the reactor, and nothing else. `service/src/main/webui` is
never *scanned*: it is a gitlink to the SPA's own repository, which the catalog lists in its own
right. **The gitlink itself is a pin**, which is a different fact from what it contains — one line
this repository owns and can move, where its contents belong to the submodule's own row.

- **A gitlink's name is the URL's basename**, `qits-artifacts-frontend`, not the `[submodule "..."]`
  entry's: a rename leaves the section behind and the url is what a clone resolves. Both spellings on
  this platform give the same answer — the wrapper writes them relative, the service repositories
  absolute.
- **A gitlink's version is a COMMIT SHA**, read from the mode-`160000` entry in the tree rather than
  from `.gitmodules`, which names a submodule and never its version. A git host that does not report
  that sha yields **no gitlink pin at all** rather than one at a guessed version — see *Rollout
  needs*.
- **An unparseable `.gitmodules` is not `CONFIG_ERROR`.** That status is for
  `.config/qits/maintenance.yml`, this service's own configuration surface. `.gitmodules` is git's
  file: what parses is used and the rest is dropped.

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

## Grouping — the kind split, and `.config/qits/maintenance.yml`

**The default grouping is the pin's KIND.** A repository that configures nothing gets two groups:
`dependencies` claims every `INTERNAL` pin and `external` claims every `EXTERNAL` one, so the
platform's own releases travel on `maintenance/dependencies` and everybody else's on
`maintenance/external`. The two halves are found by different schedules and reviewed by different
eyes; one branch carrying both made a nightly internal bump wait behind an opinion about a framework
major. `dependencies` keeps its name and its branch — it is now the internal half of what it used to
be all of.

A repository that wants a FINER grouping than those two halves writes one:

```yaml
groups:
  - name: angular
    deps: ["@angular/*", "@qits/angular"]
  - name: quarkus
    deps: ["io.quarkus:*", "io.quarkus.platform:*"]
```

A group's name is also its branch: `maintenance/angular`. `deps` are globs — `*` and `?` only, over
the flat dependency name — and **a pin matching two groups belongs to the first declared**. The kind
pair is appended AFTER whatever the file declares, so a configured group always claims first and
what none of them claimed still splits by kind. A file that declares `dependencies` or `external`
itself keeps its own globs under that name, and only the other half is appended. **Invalid yaml is
`CONFIG_ERROR` on the repository row and nothing is bumped for it** — falling back to the default
grouping would put changes on a branch the author configured against.

`GroupDto.kind` is `INTERNAL`, `EXTERNAL` or null: how the group claims, which is a different
question from `source` (whether the repository asked for the grouping at all).

The same file also carries `ignore:`, which takes a whole **ecosystem** off the repository:

```yaml
ignore: [gitlink]        # maven | npm | docker | gitlink
```

Grouping says which branch a bump rides on; `ignore` says the pin is not one at all. An ignored
ecosystem is **not parsed, not stored, not grouped and never pending** — the manifests it would have
read are not even fetched, and because an inventory is replaced wholesale, pins an earlier scan
stored disappear on the first scan after the line is committed. **An unknown ecosystem name is
`CONFIG_ERROR`**, like any other mistake in this file: a typo quietly dropped would read as a working
opt-out while the ecosystem the author meant to protect went on being bumped nightly.

The case it was built for is the **qits-qits wrapper**, whose forty-seven submodule gitlinks are
deliberately lagging bank markers rather than version pins — its own README says they exist so
`git submodule update --init` works on a fresh clone while the submodules follow their branches, and
every entry carries `ignore = all` for the same reason. Without the opt-out this service would read
those forty-seven lagging shas as forty-seven upgrades and open a nightly bump against a doctrine
the repository states in writing. The mechanism is general; the wrapper is why it exists.

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

**Neither rule reaches a `gitlink`, and forcing one would be arithmetic on a hash.** A gitlink pin is
a commit sha, which no order ranks and which is neither a release nor a prerelease. So the question
is the only one that can be asked honestly — *is the submodule pinned at the commit the newest
release was cut from* — and **both shas have to be known** for the answer to be no: `mt_latest`
carries the release's commit in `source_url` as `sha:<hex>`, and a row without one offers nothing.
The change's `to` is then the calver **version** (the step fetches `refs/tags/<to>`) while its `from`
is the sha the tree holds now.

## The bump

**Two callers ask for one: a person, and the clock.** `POST
/repositories/{name}/groups/{group}/bumps` is the button, on any group. The clock is
`schedule/BumpSchedule` at 02:00, and it asks for the INTERNAL group (`dependencies`) of every OK
repository that has something pending there and no bump already going — the external half and a
repository's own configured groups are manual-only. **No scan bumps anything any more**, whoever
triggered it; the old `bump.auto` tail of a SCHEDULED scan is gone, because a scan's schedule is set
by how fast facts go stale and a bump's by when a branch is welcome.

**One nightly bump coalesces every release since the last one.** The changes are frozen onto the row
at request time, so five internal releases between two nights are ONE branch push, one CI build, one
release request and one release — not five of each. That is the whole of the storm fix.

`POST /repositories/{name}/groups/{group}/bumps` freezes the group's pending changes onto an
`mt_bump` row and queues it. The row id travels as the CI event's `eventId`, which is the dedupe key
— a dispatch whose answer was lost records no second run when it is retried.

```json
{ "name": "MaintenanceBump", "eventId": "<mt_bump.id>",
  "payload": { "repository": "qits-ci-service", "group": "dependencies",
               "branch": "maintenance/dependencies", "baseRef": "main",
               "changes": [ {"ecosystem":"maven","manifestPath":"pom.xml",
                             "name":"eu.wohlben.qits:qits-eventstream",
                             "from":"2026.811.1","to":"2026.821.3",
                             "location":"property:qits.eventstream.version"} ] } }
```

`location` is honoured for maven only; npm edits whichever section holds the entry, docker anchors on
the image name, and gitlink restates the path. It is sent anyway. **A gitlink change is the one whose
`manifestPath` names no file**: it is the directory the submodule sits at, and the step writes a
`160000` index entry there after fetching the tag from the sibling repository its `name` addresses. `from` is never a precondition — a manifest already at
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

| run | branch | bump | `mt_branch` | release ask |
|---|---|---|---|---|
| SUCCESS | moved | `SUCCEEDED` | `PUSHED` | **asked** |
| SUCCESS | unmoved | `NOTHING_TO_DO` | unchanged | not asked — nothing was pushed |
| red | unmoved | `FAILED` | `FAILED` | not asked |
| red | **moved** | `FAILED` | `STALE` — the push is ff-only and never forced, so a branch that moved anyway is a person's commit. They own it now. | not asked — releasing somebody else's commits on their behalf is the one thing this must never do |

### The release ask

**A bump that pushed a branch asks for it to be released, itself.** It calls

```
POST {projects-url}/projects/api/repositories/<repoId>/release-requests
{ "branch": "maintenance/dependencies",
  "summary": "bump(dependencies): 5 dependencies" }
```

and gets back `{"request": {id, state, backingBranch, mergedSha, …}}`. **Nothing merges and nothing
is released at that call** — a release REQUEST is OPENED, qits-projects folds `main`, this branch and
the repository's released tags still in flight onto `release/<id>`, the quality gates settle that
fold, and Auto Release tags it once they pass. **The train's job ends here**: this service does not
poll the request, wait for a version, or record a release.

- **The repository is addressed by its CATALOG ID** — `mt_repository.catalog_id`, the `id`
  qits-projects' own listing answers, which `CatalogReader` has copied onto the row since V5. That
  route resolves its path parameter against qits-projects' repository table and nothing else, so the
  name and the project cannot address it. A row with no catalog id records a refusal; the next scan
  fills the column and the next bump asks with it.
- **`summary` is the commit subject shape the bump's own commits carry**, word for word from
  `.config/qits/ci-platform-event-maintenance-bump.yml`. The `n` is what was ASKED FOR, and it
  cannot be what a commit says: one bump is up to two commits and each counts what its own step
  applied. It doubles as the fold's commit message.
- **No `expectedSha`, and that is a deliberate loss.** qits-workspaces' door armed a request at the
  instant it was asked, so a head that had moved in between had to be a refusal. A release request
  is re-folded and re-gated on every push to any of its named sources, so a commit landing after the
  ask is gated rather than smuggled in — continuous re-gating replaces the pin.
- **No `requester`.** The field states whom a machine peer acts for, and a bump has no such person:
  a nightly one was asked for by a clock and a manual one records no operator. Omitted,
  qits-projects attributes the request to this service's own identity.
- **The ask is CONVERGENT**, which is what makes a retry of it free: a branch already participating
  in an open release request answers that request rather than opening a second one.

**`mt_bump.release_request_id` holds the answer, and NULL is the one value that means work is owed.**

| value | what it means |
|---|---|
| a request id | a release request is open and this bump names it. It is OPEN, not released — follow it in qits-projects |
| `converged` | there was nothing to hold on to — a 2xx with no id, or the branch was gone before the ask could be made |
| `refused` | a 4xx that is not an auth failure: a refusal a retry cannot fix. `message` says which, and the next nightly bump of the group asks again from scratch |
| null | the ask is still owed, and the sweep re-attempts it |

**A qits-projects that will not answer NEVER flips the bump's status.** The bump succeeded: the run
was green and the branch moved, both facts about this service's own work. A transport failure, a 5xx,
or a 401/403 leaves the row `SUCCEEDED` with a sentence on `message` and the column null, and the
poll sweep asks again on the next tick. **The retry is bounded by the branch, not by a counter**: it
stops when the request exists or when `SCMDeleteBranch` makes the branch NONE — which is also what a
landed release leaves behind, since a request's named sources are deleted when it lands. A counter
would additionally have to be right about how long qits-projects may be down for.

## The event bus

**This service subscribes and publishes nothing.** Two durable listeners on the platform's
`qits-eventstream` bus turn four facts other services know into inventory writes within seconds,
where v1 waited up to six hours for a poll.

| listener | `consumerId` (storage — never change it) | events | what it does |
|---|---|---|---|
| `bus/SoftwareReleaseListener` | `maintenance-internal-latest` | `SoftwareRelease` (qits-ci) | moves `mt_latest` **forward only**, so every pin of that dependency is pending the moment the package is in the registry |
| `bus/ScmEventListener` | `maintenance-branch-tracking` | `SCMRelease` (qits-projects), `SCMDeleteBranch`, `SCMPublishCommit` (qits-githost) | records every release as the latest of a **gitlink**, clears a maintenance branch when it is deleted, and re-reads one repository's manifests after a push to its main branch |

- **`SoftwareRelease` is the only writer of `mt_latest` that moves it forward only**, and that is the
  difference between an announcement and a poll. A poll ASKS a registry what the newest version is
  and the answer replaces what was there, downgrades included; an announcement is evidence that THIS
  version exists and never that a higher one does not. Without the guard, a catch-up frame from
  yesterday would rewind a column this morning's scan filled, and the whole inventory would report
  that dependency as up to date until the next scan. `packageName` joins `mt_pin`'s naming directly:
  maven `g:a`, npm `@scope/name`, docker `qits/<name>`. `daemon` and `docs` releases settle — they
  are real facts and nothing pins them.
- **`SCMRelease` is also the ONLY source of a gitlink's latest.** There is no registry to poll — a
  submodule is a git repository and nothing publishes one — so the daily scan neither fills that row
  nor clears it, and `LatestResolver.resolvable` refuses the ecosystem outright. Every release is
  recorded, not only those of repositories something pins today: the gate would be wrong in the one
  direction that costs, leaving a repository that grew a submodule between two releases with no
  latest for weeks. Two facts are written — the calver `version`, which is what the step fetches as
  `refs/tags/<version>`, and the **commit that tag resolves to**, carried in `source_url` as
  `sha:<hex>`. The tag is read from the git host rather than correlated with the `SCMPublishCommit`
  a moment earlier: a release is an atomic push of the branch and its tag, so one read answers it,
  while pairing two publishers' frames by repository and time is wrong exactly when two releases are
  close together. A tag the git host does not hold is settled; a git host that cannot be *asked* is
  thrown, because nothing else ever writes this row.
- **`SCMRelease` says nothing about a maintenance branch any more, and `BranchState.RELEASED` is a
  word nothing writes.** It used to: qits-workspaces' door published the event naming the branch it
  had just tagged over, which was the one fact nothing else could tell this service. A release is now
  a tag on a release request's fold, `release/<id>`, published by qits-projects — so `branch` on that
  event names the fold and never a `maintenance/` one. A maintenance branch's whole ending is the
  `SCMDeleteBranch` that follows the release (a request's named sources are deleted when it lands),
  which is the same signal a person deleting it by hand sends, and `NONE` — "the next bump starts
  fresh from main" — is the right answer to both. That delete is also the only thing that clears a
  `STALE` row. `RELEASED` stays in the enum because old rows hold it.
- **A push to a repository's own main branch queues a scan of that ONE repository**, through the same
  path `POST /scans {repository}` takes, with `trigger: EVENT`. It **never bumps** — a push changes a
  manifest, and whether the pending set becomes a branch is still the clock's standing instruction or
  a person's press. A burst of pushes is debounced against a scan of that repository already queued
  or running.
- **`SoftwareRelease` makes a SECOND write, and it is a different fact.** `mt_latest` says a version
  EXISTS; an `mt_artifact` row says THIS release has contents worth reading, PENDING, picked up off
  the worker queue afterwards. The row is written whether or not the column moved — a catch-up frame
  is not the newest version and its contents are still unrecorded. See **The dependency graph**.
- **The daily scans are the reconciliation belt, not the mechanism.** The internal cron moved from
  every six hours to 00:30 daily when these landed. It still covers three things no listener can: an
  event that was never published or was settled as poison, a repository added to the catalog (which
  announces nothing here), and the window after a new consumer starts at the head of the log and
  skips everything published before it.
- **Delivery is durable, so a disconnect is a delay rather than a hole.** A claim and the handler run
  in one transaction and a watermark is paged forward from qits-events' log at startup and on a
  schedule. The failure rule is the seam's: a payload that will not parse, or one naming a repository
  or group this inventory does not hold, is poison — a WARN and a settle; a database that will not
  answer is left to throw, and the event stays owed for the next sweep.
- **The consumed payloads are TRANSCRIPTIONS of records in three other repositories**, decoded into
  local records so no foreign jar is on the path, and pinned by `bus/ForeignEventContractTest`. A
  rename over there is a change to that file in the same campaign.
- **The bus brings a second database.** `.config/qits/deployments.yml` declares
  `postgresql:eventstream:qits_platform_maintenance_eventstream`, and the resource name is
  load-bearing — the jar reads `QITS_RESOURCE_EVENTSTREAM_*`. It is dark in `%dev` and `%test`
  (`qits.eventstream.enabled=false`), and **dark is not absent**: the datasource is opened and
  migrated at boot regardless.

## The dependency graph — what a release CONTAINS

**An SBOM says what a released artifact CONTAINS; `mt_pin` says what a bump EDITS.** They are
related by `(ecosystem, name)` and they never merge, because neither can answer the other's
question:

- **an SBOM cannot name a pom property.** It holds resolved coordinates and versions; a bump needs
  the LINE — `property:qits.eventstream.version` — which only a manifest read gives.
- **a pin cannot see a transitive.** A manifest holds what its author wrote down; everything the
  resolver pulled in behind it exists only in the built artifact's bill of materials.

So an inventory built from SBOMs would be unbumpable, and an inventory built from manifests cannot
answer "who ships a copy of this". Both are kept, joined at read time, and neither is derived from
the other.

**The row is the OUTBOX.** A `SoftwareRelease` frame writes an `mt_artifact` row PENDING and
returns; the document is fetched afterwards on the one worker thread. A listener that fetched inline
would hold a bus claim open across another service's HTTP call, and a slow qits-artifacts would turn
one release into an event redelivered for ever.

**A 404 is MISSING, it is the ORDINARY answer, and nothing retries it.** The SBOM route is newer than
most of what this platform has released, so most coordinates have no document — and a released
version is immutable, so asking again tomorrow asks about the same bytes. What supplies an answer is
the NEXT release of that artifact, which brings its own row; a person who knows a document has since
been stored asks by hand with `POST /artifacts/ingest`.

**Direct is the root component's own `dependsOn` list and nothing else.** That is the whole value of
reading the document: a direct component is something a manifest could hold a line for, and a
transitive one is something no line anywhere names. A component whose purl names a world this
service does not inventory (`pkg:golang/…`) is stored with a **null ecosystem** — shown, never
matched.

Three tables, and the only foreign keys in this schema: `mt_artifact` (one row per released
version), `mt_artifact_component` (what it contains, with the purl verbatim), `mt_artifact_edge`
(who pulled in whom — adjacency, not a closure, because the question is the PATH).

**`SoftwareRelease.repository` is qits-projects' ROW ID, not the catalog name, and
`mt_repository.catalog_id` (V5) is the translation.** Measured live on 2026-09-02: the field arrives
as `daf73ae4-…`, so `mt_artifact.repository` filled up with uuids while every read that joins it —
the detail page's transitives, `GET /repositories/{name}/dependents`, and `DependentDto.repository`,
which the client renders as the link to that page — joins on the NAME, and all of them answered
nothing without saying so. The catalog is where both spellings are known at once: qits-projects'
listing answers `id` beside `name`, `CatalogReader` keeps it, and every scan writes it. The frame is
resolved at the WRITE now (`SoftwareReleaseListener`), and `ArtifactGraph` translates in both
directions at READ time for the rows written before that — an id the catalog does not know passes
through untouched in either arm, because an unknown spelling must not lose the fact that a release
said it.

## API

Under `/maintenance/api`, path-routed on every vhost. Every route takes `qits:admin` (a person, via
the edge's `X-Qits-User` / `X-Qits-Roles`) or `qits:system` (a machine, via a bearer). There is no anonymous route. Every
error body is `{"message": "..."}`.

```
GET  /repositories                                → [{name, project, lastScanAt, headSha, status,
                                                      message, pending,
                                                      groups:[{name, source, kind, branch, state,
                                                               headSha, pending}]}]
GET  /repositories/{name}                         → the above, plus
                                                    pins:[{manifestPath, ecosystem, name, version,
                                                           range, kind, latest, latestError,
                                                           pending, group, location,
                                                           scope: "DIRECT"}]
                                                    transitives:[{ecosystem, name, version, via,
                                                                  behind}]
GET  /dependencies?name=<glob>[&kind=]            → [{ecosystem, name, latest, checkedAt, error,
                                                      pins:[{repository, version, manifestPath,
                                                             pending}]}]
GET  /dependencies/dependents?ecosystem=&name=    → {ecosystem, name, latest,
     [&all=true]                                     dependents:[{artifactEcosystem, artifactName,
                                                                  artifactVersion, repository,
                                                                  embeddedVersion, direct,
                                                                  occurredAt, sbomStatus}]}
GET  /pins                                        → {generatedAt,
                                                     repositories:[{name, status, lastScanAt,
                                                                    headSha}],
                                                     pins:[{ecosystem, name, version, repository,
                                                            manifestPath}]}
                                                                503 the inventory holds no row at all
GET  /artifacts                                   → [{ecosystem, name, repository, latest, version,
                                                      occurredAt, sbomStatus, dependentCount,
                                                      behindCount}]
POST /artifacts/ingest {ecosystem,name,version}   → 202 {id}    400 unknown ecosystem
GET  /repositories/{name}/dependents              → {repository,
                                                     artifacts:[{ecosystem, name,
                                                                 dependents:[…as above]}]}
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
- **`kind` on `/dependencies` is INTERNAL or EXTERNAL and nothing else.** REACTOR and UNRESOLVED are
  refused with a 400 rather than answered with an empty list: neither is a half of the split the
  filter serves, and an empty list would read as "there are none of those". The filter is
  server-side because the two halves are two pages — the same split every default group, every
  branch and both scan schedules already make.
- **`/dependencies` and `/dependencies/dependents` are two routes because they are two facts.** A
  pin is a line a bump can edit; a dependent is a component inside a published package, transitives
  included. The default view of `dependents` is the NEWEST released version of each dependent —
  forty-nine older releases of one library are answers about versions nobody can change any more —
  and `all=true` is the archaeology.
- **`transitives` on the repository detail is what its RELEASES contain that no manifest names.** It
  is read from the newest INGESTED document of each artifact the repository publishes, with anything
  that is also a pin removed (that row is already on the page, with a verdict). `via` is the direct
  component whose subtree pulled it in — the first by name where several do, because a graph has
  many paths and a page needs one. **Empty means "we do not know"**, not "there are none": a
  repository whose releases have no stored document is the ordinary state during the rollout.
- **`/pins` is the artifact GC's dependency-pin source and is read by a machine, not a page.**
  qits-artifacts collects the registry against a few keep-sets read once per run — what the running
  services deploy, what the images name, and this one: every internal maven, npm and docker version
  a catalogued repository's main branch still references. Rows are served **as stored** — no dedupe
  and no folding, because the consumer folds and each row names the repository and manifest that
  make a keep decision explainable — in one total order (ecosystem, name, version, repository,
  manifest), so two reads over an unchanged store answer the same bytes. `gitlink` is excluded: it
  is INTERNAL by construction and its version is a commit sha, which is not an artifact anything
  could collect. `repositories` carries the freshness the consumer judges the answer by.
- **An inventory with no rows at all answers 503 rather than an empty keep-set.** The consumer is
  fail-closed on a source it could not read — that run deletes nothing — and treats an answer as
  authoritative, so "this service has never scanned" must never arrive as "nothing on the platform
  is referenced". A scanned inventory in which some repositories are UNREACHABLE still answers 200:
  those rows keep the pins their last good scan read, so the keep-set is stale rather than absent,
  and `status` and `lastScanAt` say so on the row.
- **`scope` on a pin is always `DIRECT`, and it is a constant on purpose.** The detail now serves two
  lists whose rows look alike, and a client rendering them in one table needs the distinction on the
  row rather than derived from which array it came out of.

The document is at `/maintenance/q/openapi`, the browsable UI at `/maintenance/q/swagger-ui`, and
readiness at `/maintenance/q/health/ready`. The client is served at `/` — this service has a host of
its own, `maintenance.<env>.<domain>`, and the `/maintenance` segment is the wire surface alone.

## Configuration

Every key below is defaulted in the domain jar
(`maintenance/src/main/resources/META-INF/microprofile-config.properties`) and overridable by
environment without a rebuild.

| key | default | what it decides |
|---|---|---|
| `qits.maintenance.targets.projects-url` | `http://qits-projects:8080` | where the catalog is |
| `qits.maintenance.targets.githost-url` | `http://qits-githost:8080` | where the manifests are |
| `qits.maintenance.targets.ci-url` | `http://qits-ci:8080` | which CI applies a bump |
| `qits.maintenance.targets.artifacts-url` | `http://qits-artifacts:8080` | where the SBOM documents are — a bare host, because the route's whole path belongs to the caller |
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
| `qits.maintenance.scan.internal.cron` | `0 30 0 * * ?` | the internal scan, 00:30 daily — the reconciliation belt behind the bus |
| `qits.maintenance.scan.external.cron` | `0 0 1 * * ?` | the external scan, 01:00 daily |
| `qits.maintenance.sbom.sweep-cron` | `0 5 * * * ?` | re-queue artifact rows still PENDING, hourly. It never retries MISSING or FAILED |
| `qits.maintenance.time-zone` | `UTC` | the zone both crons are read in |
| `qits.maintenance.bump.enabled` | `true` | whether a branch may be pushed at all |
| `qits.maintenance.bump.internal.cron` | `0 0 2 * * ?` | the nightly INTERNAL bump, 02:00 — after both scans, so the inventory it reads is today's |
| `qits.maintenance.bump.internal.auto` | `true` | whether the clock asks for those bumps. **The live deployment holds it `false` until the pre-split branches are drained** |
| `qits.maintenance.bump.external.auto` | `false` | **reserved.** External bumps are manual-only; setting it logs a WARN once and does nothing |
| `qits.maintenance.bump.poll-interval` | `15s` | how often an unfinished bump is looked at |
| `qits.maintenance.environment` | `dev` | which environment's CI is recorded on a bump row |
| `qits.auth.machine.audience` | `qits-platform-maintenance` | this service's own id at qits-platform-idp |

**The registry keys carry a PATH as well as a host**, because a registry is mounted under a prefix
and the prefix names the repository row it serves. Moving a row is then a deployment's decision.
**`targets.artifacts-url` deliberately does not**: `/artifacts/sboms/…` is qits-artifacts' own API
rather than a mount, so its whole path belongs to the caller and lives in the code.

**`bump.enabled` stops the button as well as the schedule**, which is the point: a platform that
wants to watch what *would* change for a week reads the inventory and pushes nothing.
`bump.internal.auto` only stops the clock. **No scan bumps under any setting** — pressing Scan asks
what is out of date, pressing Bump asks for a branch, and the clock's standing instruction is its own
cron.

**`QITS_MAINTENANCE_BUMP_AUTO` is now inert.** Nothing reads it; MicroProfile does not fail on an
environment variable no key claims, so a live platform still carrying it is misleading rather than
broken. Remove it from the deployment's extras at the next edit of that file.

**One tier service by configured url.** qits-ci is per environment (`dev-qits-ci`) while this
service is platform tier, so a live platform injects the qualified name. Known debt, the same one
qits-configuration and qits-platform-orchestrator carry.

**Outbound credentials** are five named oidc clients — `projects`, `githost`, `ci`, `artifacts`,
`mirror` — all `client-id=qits-platform-maintenance`, all shipped
`client-enabled=false`. A token is cut for one service, which is why there are five; only the audience
differs, and it is the one value not defaulted, because it can be environment-qualified. A deployment
turns one on with

```
QUARKUS_OIDC_CLIENT_CI_CLIENT_ENABLED=true
QUARKUS_OIDC_CLIENT_CI_CREDENTIALS_SECRET=<this service's idp client secret>
QUARKUS_OIDC_CLIENT_CI_GRANT_OPTIONS_CLIENT_AUDIENCE=dev-qits-ci
```

Off, calls go out with the forward-auth pair alone (`X-Qits-User: qits-platform-maintenance`,
`X-Qits-Roles: qits:system`), which every call carries regardless.

**The release ask needs no client of its own.** It is a qits-projects route, so it rides the
`projects` credential every catalog read already mints; the route admits `qits:admin` and
`qits:system`, and `qits:system` is what every call here carries. There was a sixth client once —
audience `qits-workspaces`, for the release door — and it went with the door. A deployment still
setting `QUARKUS_OIDC_CLIENT_WORKSPACES_*` is setting keys nothing reads.

**The store** is its own PostgreSQL database, `qits_platform_maintenance`, declared by
`resources: postgresql:db` in `.config/qits/deployments.yml`. Ten tables in three families:
`mt_repository`, `mt_pin`, `mt_group`, `mt_latest` (an inventory a scan replaces wholesale);
`mt_scan`, `mt_branch`, `mt_bump` (a log of what was asked and what came back, derivable from
nothing); and `mt_artifact`, `mt_artifact_component`, `mt_artifact_edge` (what each released
artifact CONTAINS, replaced per artifact by each ingest — and the only foreign keys in the schema,
because both ends are this context's own).

**A second database, `qits_platform_maintenance_eventstream`**, declared by
`postgresql:eventstream:<name>` beside it, holds the bus's outbox and the two durable consumers'
claim ledger and watermarks. It is qits-eventstream's, with its own Flyway lineage, and it is never
shared with the store above.

## Rollout needs

**The idp client `qits-platform-maintenance` is the orchestrator's shape plus one claim.** The
claim is not optional — it is a route this service cannot use without it.

| what | why |
|---|---|
| roles `qits:system`, `qits-platform:system` | the same pair qits-platform-orchestrator's client carries. It covers qits-projects' catalog, qits-githost's content policy, qits-ci's trigger and — since qits-ci a3ecce2 — the read-only run and repository routes the bump poller follows. |
| a qits-projects serving `POST /repositories/{repoId}/release-requests` | **The release ask, and it needs nothing new here.** That route admits `qits:system` beside `qits:admin`, so the `projects` credential already opens it and no `qits:admin` lands on a service — the bootstrap's "qits:admin is a person's role" doctrine stands. Until that qits-projects release is deployed the ask is a 404, recorded as a refusal; the next nightly bump of the group asks again. |
| claim `project` = `*` | qits-ci's trigger calls `machineAuth.requireProject("*")`, which passes only for a token literally granted every project. The bump names one repository but the trigger route demands them all. Today the only such grant is qits-platform-artifacts'; this service needs its own. |
| audiences `<env>-qits-ci`, `qits-projects`, `qits-githost` | a token is cut for one service. qits-githost ships `qits.auth.machine.required=true`, so its content reads need a real bearer addressed to it. |
| audiences `qits-platform-artifacts`, `qits-platform-mirror` | **not needed today** — the registry routes and the mirror's proxies are unguarded on qits-net. The two clients ship disabled for the day the edge's rule reaches the inside. |

In `qits-configuration` / `.qits-bootstrap.env` terms that is a client with
`_ROLES` carrying `qits:system,qits-platform:system` (unchanged), `_CLAIMS_PROJECT: "*"`, and
`_AUDIENCES` listing the four services above.

**Gitlinks need the git host to report a tree entry's sha — written (qits-githost 33b0ccf), not
yet deployed.** That commit teaches `GET /git/<project>/<repo>/tree/<rev>[/<path>]` to answer a
gitlink entry as `{"name","type":"commit","sha","mode":"160000"}` while every other entry keeps its
two-field shape; `blob`/`tree` of a gitlink path stay 404 because the sha on the entry is the whole
answer. Against the deployed githost, which still collapses a gitlink to `blob` with no `mode` and
no object name, this service reads `mode` and `sha` off a tree entry when they are there and pins
**nothing** when they are not — a made-up version would be compared by the pending rule and then
applied into somebody else's repository. Both spellings (`mode` or `type`) are accepted here. Until
that githost release deploys, the fifteen `ci-event-upstream-frontend.yml` hop files still do the
work and nothing is lost.

**The wrapper needs `.config/qits/ci-platform-event-maintenance-bump.yml`** — the platform-level
pipeline that answers `MaintenanceBump` — and a qits-ci release carrying platform pipelines. Until
both exist every bump ends FAILED with `no run recorded for MaintenanceBump`, which is the honest
answer rather than a silent success.

**The nightly bump needs the branches drained first.** Every `maintenance/dependencies` branch that
exists right now carries MIXED commits — internal and external together, from before the kind split
(see `V2__internal_external_split.sql`). Release or delete them, then flip
`QITS_MAINTENANCE_BUMP_INTERNAL_AUTO=true`. The jar defaults it true; the deployment holds it false
until that is done, because the first internal bump after the split would otherwise push onto a
branch still carrying an external upgrade nobody reviewed under that name.

**The bus needs one deploy and one check, in that order.**

- **Deploy A — the resource.** The second `resources:` line is read at the built sha, so the first
  deployment carrying it is what creates `qits_platform_maintenance_eventstream` and injects
  `QITS_RESOURCE_EVENTSTREAM_*`. The variables have no defaults on purpose: a container started
  without them dies at Flyway naming what is missing, and the health gate keeps the previous one — a
  loud, safe failure rather than a fallback store nobody meant. `QITS_EVENTS_URL` defaults to the
  qits-net alias `http://qits-events:8080` and needs nothing.
- **Deploy B — confirm the maven `packageName` against ONE live frame.** The three name spellings
  the release listener assumes are read off the `artifacts:` declarations committed in the
  platform's own `ci-event-release.yml` files, which qits-ci copies into the payload verbatim; maven
  is the one worth checking by hand, because it is the only name carrying a separator and a frame
  arriving as `qits-eventstream` rather than `eu.wohlben.qits:qits-eventstream` would write a row
  nothing joins to and say nothing about it. After the first internal release following the deploy,
  read that frame's payload and compare it with the same artifact's `mt_pin.name`.
- **Both consumers start at the HEAD of the log.** `maintenance-internal-latest` and
  `maintenance-branch-tracking` are new storage keys, so every release published before the deploy
  is skipped. That is what the 00:30 scan is for; nothing has to be replayed by hand.

## Building and testing

```
./mvnw clean verify -Dquarkus.http.test-port=0
```

Green on a clone with **no docker and no credentials** — the suite spawns its own PostgreSQL from a
Maven artifact (zonky) and the six peers are faked. It needs two things: a **node on PATH** and an
**initialised webui submodule**, because `verify` runs `package` and `package` is where Quinoa
builds the client. `./mvnw test` needs neither — Quinoa is off in test mode.

`-Dquarkus.http.test-port=0` is not optional on the deployment host: Quarkus' default test port 8081
is the platform's own npm registry there.

Integration tests are skipped by default. `-DskipITs=false` runs `PackagedSurfaceIT` against the
fast-jar; `-Dnative` builds the GraalVM binary (`.sdkmanrc` names `25.0.2-graalce`) and runs it
against that.

The image is `docker/Dockerfile`, built from the repo root with the client bundle already in the
context — see `AGENTS.md`.
