-- WHAT A RELEASED TREE DECLARED, beside what a released artifact CONTAINS.
--
-- THE PROBLEM THESE TWO TABLES EXIST FOR, measured live on 2026-09-08. The adoption journey of
-- `qits-ui-components-jslib 2026.906.164412` reported fifteen frontends at depth 1 and fifteen
-- services at depth 2, every one of them PENDING, while every one of them had in fact picked the
-- release up weeks earlier. The evidence rule was one kind and one only: a component row in the
-- CONSUMER's own released SBOM naming the released coordinate. On this estate that rule can never
-- fire for the npm world:
--
--   * a frontend publishes no registry artifact at all. Its release is a git tag, and what consumes
--     it is the embedding service's gitlink bump — so there is no `mt_artifact` row of the frontend
--     to hold a component, and `dependents(npm, @qits/ui-components)` answered the empty list;
--   * a service's docker-image SBOM holds maven components only — the compiled Angular dist carries
--     no npm metadata — so `qits-ci-service`'s newest document listed 239 maven components and 0
--     npm ones, and the frontend→service hop was equally unprovable.
--
-- WHY A PIN IS ALLOWED TO BE EVIDENCE HERE, when `AdoptionEvaluator` was right to refuse one. The
-- objection to a pin was never that it is a pin: it was that a pin read at `main` is a fact about
-- somebody's WORKING TREE. Nothing polls it, it moves under you, and it is revertible — so "adopted"
-- computed from one would flicker. A pin read at `refs/tags/<version>` is a different fact. A tag is
-- immutable and it is tied to the consumer's own released version, which is exactly what the
-- evaluator reports as `adoptedVersion`; so the philosophy already written into that class — the
-- evidence is about a RELEASE, not a working tree — is satisfied by the tag rather than violated by
-- the pin. Two evidence kinds, and they answer two different questions about one release: an SBOM
-- says what it CONTAINS, transitives included, and these tables say what it DECLARED.
--
-- IT IS A LOG, LIKE `mt_artifact`, AND UNLIKE `mt_pin`. A scan replaces `mt_pin` wholesale on every
-- run, because the question there is "what does the main branch declare TODAY". A row here is the
-- reading of one immutable tree and is never invalidated by anything a repository does afterwards.
-- Re-recording the same release replaces its pins rather than adding to them, because the second
-- reading of one tag is a correction of the first and never a second fact.
--
-- WHO WRITES THEM. `bus/ScmEventListener` on every `SCMRelease`, one hop after it records the
-- gitlink latest; and `work/ReleaseLedgerBackfill` at boot, for every gitlink `mt_latest` row whose
-- release predates this migration. Both go through `adoption/ReleaseLedger`.
--
-- NO FOREIGN KEY TO ANY OTHER CONTEXT, and none between these two either — the flat shape every
-- table in this schema outside V3's graph takes. `repository` is a catalog name, which is
-- qits-projects' fact, and a release may name a repository this inventory has never scanned.

create table mt_release (
    id uuid not null,

    -- THE CATALOG NAME, ALWAYS, and both writers are held to it: the listener writes
    -- `SCMRelease.repositoryName` (the name, with the row id only as a fallback for a payload that
    -- carries no name) and the backfill writes `mt_latest.name`, which is a gitlink's name and is a
    -- repository name by construction. That is what lets `AdoptionEvaluator` compare this column
    -- with a closure entry directly, with none of the id↔name translation `mt_artifact.repository`
    -- needs (see V5).
    repository varchar(255) not null,

    -- The released version — the calver the tag is named after, `refs/tags/<version>`.
    version varchar(255) not null,

    -- THE COMMIT THE TAG RESOLVED TO, which is what makes the gitlink hop provable. An embedder
    -- pins a submodule at a commit and never at a version, so "which release of the frontend is
    -- this service carrying" is answered by looking this sha up among the frontend's own releases.
    -- Full 40-hex as everything here writes it; `latest/GitlinkSha.same` compares abbreviation
    -- tolerantly, because a pin read out of a tree is whatever git recorded.
    sha varchar(64) not null,

    -- The publisher's moment, never a clock reading taken here where one is available: it is what
    -- the "earliest release that carries it" answer is ordered by, and a catch-up frame processed
    -- today announces a release from last week. The backfill has no frame and uses the
    -- `mt_latest.checked_at` of the row it is filling in, which is the closest honest stamp it has.
    occurred_at timestamp(6) with time zone not null,

    primary key (id)
);

-- ONE ROW PER RELEASED VERSION, and this is the whole idempotence story. A durable redelivery, a
-- backfill re-run and a re-record of a release whose tree was re-read all aim at this key: the row
-- is replaced in place and its pins are rewritten, so two attempts converge on one answer.
create unique index uq_mt_release_identity on mt_release (repository, version);

-- "Every release of this repository", which is the read that resolves a gitlink pin's sha to the
-- version it belongs to. One indexed read per gitlink hop.
create index idx_mt_release_repository on mt_release (repository);

create table mt_release_pin (
    id uuid not null,

    -- FLAT, no foreign key, unlike V3's `mt_artifact_component.artifact_id`. The two are otherwise
    -- the same shape and it would be defensible either way; the flat form is chosen because these
    -- rows are rewritten wholesale by a delete keyed on this column inside the same transaction
    -- that replaces the release row, so the constraint would buy nothing the writer does not
    -- already guarantee, and it keeps this pair outside the "the only foreign keys in this schema"
    -- sentence that V3 is entitled to.
    release_id uuid not null,

    -- `mt_pin`'s vocabulary: MAVEN, NPM, DOCKER or GITLINK. Unlike `mt_artifact`, GITLINK is here
    -- and is the interesting half — a submodule is how a service consumes a frontend.
    ecosystem varchar(32) not null,

    -- The dependency in `mt_pin`'s spelling — `groupId:artifactId`, `@scope/name`, `qits/<name>`,
    -- or for a gitlink the SUBMODULE'S REPOSITORY NAME, which is the url's basename.
    name varchar(512) not null,

    -- WHAT THE RELEASED TREE PINNED IT AT. A version for the three registry ecosystems and a COMMIT
    -- SHA for a gitlink, which is the same asymmetry `mt_pin.version` carries and for the same
    -- reason: `.gitmodules` names a submodule and the tree holds its commit. Nullable because a pin
    -- with no version is not one this service compares, and dropping it at write time would lose
    -- the fact that the tree declared the dependency at all.
    version varchar(255),

    primary key (id)
);

-- THE REVERSE READ, and the reason this table exists: "which releases DECLARED this coordinate" is
-- `where ecosystem = ? and name = ?`, the exact shape of V3's `idx_mt_artifact_component_name`, and
-- the evaluator joins its answer with `mt_release` in java for the same reason `dependents()` does —
-- `release_id` is a plain uuid and a join would have to be native SQL.
create index idx_mt_release_pin_name on mt_release_pin (ecosystem, name);

-- And the forward one, which is what the rewrite deletes by.
create index idx_mt_release_pin_release on mt_release_pin (release_id);
