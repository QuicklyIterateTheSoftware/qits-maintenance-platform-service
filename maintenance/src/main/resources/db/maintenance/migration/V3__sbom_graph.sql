-- WHAT A RELEASED ARTIFACT CONTAINS, beside what a manifest EDITS.
--
-- THE PRINCIPLE, AND IT IS THE WHOLE REASON THESE ARE THREE NEW TABLES RATHER THAN COLUMNS ON
-- mt_pin: an SBOM says what a released artifact CONTAINS; mt_pin says what a bump EDITS. They are
-- two different facts about two different things, and they are related by (ecosystem, name) and by
-- nothing else. They must never be merged:
--
--   * an SBOM cannot name a pom PROPERTY. It holds resolved coordinates and versions, and a bump
--     needs the LINE — `property:qits.eventstream.version` — which only a manifest read can give.
--   * a pin cannot see a TRANSITIVE. A manifest holds what its author wrote down; everything the
--     resolver pulled in behind it exists only in the built artifact's bill of materials.
--
-- So an inventory built from SBOMs would be unbumpable, and an inventory built from manifests
-- cannot answer "who ships a copy of this". Both are kept, joined on (ecosystem, name) at read
-- time, and neither is derived from the other.
--
-- WHERE THE DOCUMENTS COME FROM. qits-artifacts stores one CycloneDX document per released
-- artifact, at `GET /artifacts/sboms/{packageType}/{packageName}/-/{version}`. A 404 is the NORMAL
-- answer during the incremental rollout — most artifacts released before that route existed have
-- none, and never will — which is why MISSING is a recorded state rather than a failure and why
-- nothing retries it.
--
-- THE ROW IS THE OUTBOX. A SoftwareRelease frame writes an mt_artifact row PENDING and returns;
-- the fetch happens later, on the worker thread, outside the transaction that claimed the event. A
-- listener that fetched inline would hold a bus claim open across another service's HTTP call, and
-- a document that was slow to arrive would be an event redelivered for ever.

create table mt_artifact (
    id uuid not null,

    -- mt_pin's vocabulary, because that is the join: MAVEN, NPM or DOCKER. `daemon` and `docs`
    -- releases have SBOMs upstream and never a row here — nothing in any manifest pins them, so an
    -- artifact row for one would join to nothing and be answered by nobody.
    ecosystem varchar(32) not null,

    -- The UNQUALIFIED package name, spelled exactly as mt_pin spells it: `groupId:artifactId` for
    -- maven, `@scope/name` for npm, `qits/<name>` for docker. It is what the reverse query joins on
    -- and what goes into the SBOM route's path literally, slashes and all.
    name varchar(512) not null,

    -- The released version this document describes. One row per (ecosystem, name, version): the
    -- history is the point — "which versions of the library still carry the vulnerable transitive"
    -- has no answer if only the newest is kept.
    version varchar(255) not null,

    -- WHICH REPOSITORY PRODUCED IT, as SoftwareRelease.repository spells it. A STRING and no
    -- foreign key, exactly like mt_pin.repository: a repository name belongs to qits-projects'
    -- database, and this column is filled from an event that may name a repository this inventory
    -- has never scanned.
    repository varchar(255),

    -- When the release happened, off the event frame rather than off this service's clock. It is
    -- what "the newest version per dependent" is ordered by, so it has to be the publisher's
    -- moment: a catch-up frame processed today announces a release from yesterday.
    occurred_at timestamp(6) with time zone not null,

    -- PENDING (the row exists, the document has not been read), INGESTED (components and edges
    -- below are this document's), MISSING (qits-artifacts answered 404 — the ordinary state during
    -- the rollout) or FAILED (it answered something else, or the document did not read).
    --
    -- Not a check constraint, the same stance the four status columns of V1 take: the vocabulary is
    -- the ingest service's, in code, and a historical row keeps the word it was written with.
    sbom_status varchar(32) not null,

    -- Why the status is FAILED, in one line, for the UI and for a person deciding whether to
    -- re-ingest by hand. Null otherwise.
    sbom_error text,

    -- When the document was last read successfully. Null until one has been.
    ingested_at timestamp(6) with time zone,

    primary key (id)
);

-- ONE ROW PER RELEASED VERSION. A redelivered SoftwareRelease, and a manual re-ingest of the same
-- coordinate, both have to land on the row that is already there rather than mint a second.
create unique index uq_mt_artifact_identity on mt_artifact (ecosystem, name, version);

-- "The newest version of this artifact", which is what the newest-per-dependent view is built from
-- and what the internal-libs listing reads.
create index idx_mt_artifact_name on mt_artifact (ecosystem, name, occurred_at desc);

-- The ingest sweep: `where sbom_status = 'PENDING'`.
create index idx_mt_artifact_status on mt_artifact (sbom_status);

create table mt_artifact_component (
    id uuid not null,

    -- A REAL FOREIGN KEY, unlike every other relation in this schema, and it is allowed for one
    -- reason: both ends are this context's own tables in this context's own database. mt_pin has no
    -- FK to mt_repository because a repository name is another service's fact; a component has no
    -- meaning at all apart from the artifact it was read out of.
    artifact_id uuid not null references mt_artifact (id) on delete cascade,

    -- The document's own identifier for this component, which is what the dependencies[] adjacency
    -- refers to. Kept because it is the only thing that makes the edges below re-derivable.
    bom_ref varchar(1024),

    -- THE PURL VERBATIM, un-normalised. Stored so a wrong parse can be reproduced by hand: the
    -- columns beside it are this service's reading of this string, and the string is the evidence.
    purl varchar(1024),

    -- MAVEN, NPM or DOCKER — or NULL, which is the interesting case. A purl type this service does
    -- not map (`pkg:golang/...`, `pkg:generic/...`) leaves this null: the component is STORED and
    -- SHOWN and is never MATCHED, because a name in an ecosystem this platform does not inventory
    -- cannot be compared with anything.
    ecosystem varchar(32),

    -- The dependency name in mt_pin's spelling when the purl mapped, and the document's own
    -- component name when it did not.
    name varchar(512),

    version varchar(255),

    -- WHETHER THE ARTIFACT DECLARES IT. True for the members of the root component's own dependsOn
    -- list, false for everything else the graph reaches. The distinction is the whole value of the
    -- document: a direct component is something a manifest could hold a line for, a transitive one
    -- is something no line anywhere names.
    direct boolean not null,

    primary key (id)
);

-- THE REVERSE QUERY, and the reason this whole table exists: "which artifacts embed this
-- dependency" is `where ecosystem = ? and name = ?`, answered across every released version of
-- every internal library.
create index idx_mt_artifact_component_name on mt_artifact_component (ecosystem, name);

-- And the forward one: everything one artifact contains, for the repository detail page.
create index idx_mt_artifact_component_artifact on mt_artifact_component (artifact_id);

create table mt_artifact_edge (
    id uuid not null,

    artifact_id uuid not null references mt_artifact (id) on delete cascade,

    -- NULL MEANS THE ROOT. The document's root component is the artifact itself and has no row in
    -- mt_artifact_component — it is the mt_artifact row — so its children are recorded with a null
    -- parent rather than with a self-referencing component nothing else would ever match.
    parent_component_id uuid,

    child_component_id uuid not null,

    primary key (id)
);

-- ADJACENCY RATHER THAN A CLOSURE, because the UI's question is "what pulled this in": a nested
-- tree under each direct dependency, walked in memory for one artifact at a time. A transitive
-- closure would be the right shape for "is X anywhere below Y" and the wrong one for showing the
-- path, and it would be quadratic in a graph this service re-reads wholesale on every ingest.
create index idx_mt_artifact_edge_artifact on mt_artifact_edge (artifact_id);
