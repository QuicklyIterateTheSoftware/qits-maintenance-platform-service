-- THE RELEASE TRAIN: what one release still owes the estate, made into a thing with a shape.
--
-- WHAT IT IS. A repository releases a version. Somewhere between two and twenty other repositories
-- are supposed to end up carrying it — through a pom property, a package.json range, a Dockerfile
-- FROM line, an image pin in a deployment config, a daemon pin on a service. Today that journey is
-- a thing you reconstruct by hand out of `mt_pin ⋈ mt_latest`, one repository at a time, and
-- nothing anywhere says whether it FINISHED. A train is that journey written down once, at the
-- moment of the release, so a person can be shown a single line — "2026.905.1 of the eventstream
-- lib: nine adopters, six landed" — instead of a query.
--
-- TWO TABLES BECAUSE THERE ARE TWO NOUNS. `mt_train` is the release: one row per released
-- (repository, version), and it is the STATION every packageType of that release folds into. A
-- repository that publishes a maven artifact, an npm package, a docker image and an api-docs bundle
-- in one pipeline emits four `SoftwareRelease` frames, and all four are the same release — so all
-- four settle onto one train rather than minting four. `mt_train_node` is one expected adopter: a
-- repository that is supposed to move, and the END it moves through.
--
-- IT IS A LOG, NOT A CACHE, and that is the distinction V1 draws over its seven tables. `mt_pin`,
-- `mt_group`, `mt_latest` and `mt_repository` are a reading of somebody else's files and are
-- replaced wholesale by the next scan. These two are not derivable from anything after the fact:
-- the set of expected adopters is who pinned the released coordinate AT THE MOMENT OF THE RELEASE,
-- and a repository that dropped the dependency the next day was still owed the adoption and still
-- failed to make it. Nothing here is ever rewritten by a scan.
--
-- A TRAIN NEVER FAILS, and that is a deliberate omission from the status vocabulary below rather
-- than a gap. A train is a record of a journey, and an adoption that has not happened yet is
-- indistinguishable from one that never will — there is no deadline anywhere in this platform after
-- which a repository is declared to have refused a version. What ends a train is arrival
-- (COMPLETED) or irrelevance (SUPERSEDED, because a newer version of the same repository is out and
-- the estate should be adopting THAT). A FAILED train would be this service inventing a verdict it
-- has no evidence for.
--
-- NO FOREIGN KEY TO ANY OTHER CONTEXT, the standing rule: `repository` and `consumer` are catalog
-- names, which are qits-projects' facts, and a train may name a repository this inventory has never
-- scanned.

create table mt_train (
    id uuid not null,

    -- THE CATALOG NAME, NEVER THE ROW UUID, and this is the column where that has to be got right.
    -- The `SoftwareRelease` frame that spawns a train spells the repository as qits-projects' row id
    -- (measured live 2026-09-02, see V5); every read on this side — the node derivation's "who pins
    -- what this repository released", the supersession's "the other trains of this repository", the
    -- UI's link to the repository page — joins a NAME. The listener resolves it through
    -- `MaintenanceStore.repositoryName` before anything is written here, exactly as
    -- `SoftwareReleaseListener` does for mt_artifact.repository, and an unknown spelling is kept
    -- verbatim rather than dropped: a release of a repository nothing has scanned still happened.
    repository varchar(255) not null,

    -- The released version, as the release announced it. One train per version and the history is
    -- the point: "did 2026.903.4 ever reach the estate" has no answer if only the newest is kept.
    version varchar(255) not null,

    -- OPEN (adopters are still owed), COMPLETED (every node landed, or there were none to begin
    -- with) or SUPERSEDED (a newer release of the same repository is out).
    --
    -- Not a check constraint, the stance every status column in this schema takes: the vocabulary is
    -- `model/TrainStatus`, in code, and a historical row keeps the word it was written with.
    status varchar(32) not null,

    -- THE FRAME'S occurred_at, NEVER Instant.now(), AND IT IS THE ORDERING KEY — which is the whole
    -- reason the rule is worth a paragraph here rather than a line. Supersession is decided by
    -- comparing this column between two trains of the same repository, so a clock stamp would make
    -- the order of a CATCH-UP the order of the releases: a consumer that was down for an hour comes
    -- back and replays four releases in seconds, every one of them stamped "now" in arrival order,
    -- and the last one processed wins regardless of which was actually the newest. Off the frame,
    -- the four sort the way they were published, and a train spawned behind a newer one is born
    -- SUPERSEDED rather than superseding it.
    created_at timestamp(6) with time zone not null,

    -- When the journey ended, whichever way it ended. Null while OPEN. A degenerate train — a
    -- release nobody was expected to adopt — is COMPLETED at creation and carries created_at here,
    -- because that is the honest moment: it arrived the instant it left.
    completed_at timestamp(6) with time zone,

    -- WHICH TRAIN MADE THIS ONE IRRELEVANT. Null unless the status is SUPERSEDED.
    --
    -- A FLAT uuid with NO foreign key, unlike mt_train_node.train_id below, and the difference is
    -- deliberate. A node has no meaning apart from its train and is meaningless if that train is
    -- gone; a supersession is a REFERENCE between two independent records, and the day somebody
    -- prunes trains older than a year the constraint would either block the prune or cascade a
    -- delete through rows that were never children. It is a pointer for a reader, not a structure.
    superseded_by uuid,

    primary key (id)
);

-- ONE TRAIN PER RELEASED VERSION, and this index is the whole idempotence story. Four sibling
-- artifact frames of one pipeline, plus every at-least-once redelivery the bus makes, all aim at
-- this key: the first creates, the rest settle onto the row that is there. The spawn re-reads inside
-- its own transaction as well, so the constraint is the belt rather than the only brace — but it is
-- what makes two frames handled concurrently impossible to get wrong.
create unique index uq_mt_train_release on mt_train (repository, version);

-- SUPERSESSION'S READ, both directions of it: "the other trains of this repository, newest first".
-- A new spawn asks it twice — once for an already-newer train (am I born superseded) and once for
-- the older OPEN ones (which do I supersede) — on the listener's claim transaction, so it is in
-- front of somebody else's event throughput.
create index idx_mt_train_repository on mt_train (repository, created_at desc);

create table mt_train_node (
    id uuid not null,

    -- A REAL FOREIGN KEY, and the same justification V3's mt_artifact_component gives: both ends are
    -- this context's own tables in this context's own database, and a node has no meaning at all
    -- apart from the train it was derived for. Contrast superseded_by above, which is a reference
    -- between two peers rather than a part-of.
    train_id uuid not null references mt_train (id) on delete cascade,

    -- WHO IS EXPECTED TO ADOPT, by catalog name — a repository for LINKED and DAEMON_PIN, and for
    -- CONFIG_IMAGE_PIN the APPLICATION whose deployment config holds the pin. Those are two
    -- different namespaces that mostly agree (the `qits-ci` application is built from
    -- `qits-ci-service`), and the column holds whichever one the end kind beside it addresses:
    -- resolving them to a single identity would mean inventing a mapping this service has no source
    -- for. No foreign key, for the standing reason.
    consumer varchar(255) not null,

    -- WHAT THE CONSUMER IS, COPIED AT SPAWN rather than read through mt_repository at display time.
    -- The train is a log (see the header) and this is part of what was true when it was derived: a
    -- repository re-classified from LIBRARY to SERVICE next month does not retroactively change the
    -- kind of adoption this node was placed for. Nullable and unvalidated, exactly like the column
    -- it is copied from (V6) — a word this build does not know costs a node its typed reading and
    -- nothing else.
    archetype text,

    -- PENDING (the consumer has not taken the version), ADOPTED (it has, on a branch or in a commit)
    -- or LANDED (that adoption is released and integrated). Not a check constraint, same stance.
    --
    -- THE EVALUATION THAT MOVES THESE IS NOT IN THIS MIGRATION'S FEATURE. What lands here now is the
    -- spawn: the station and its nodes, all PENDING. The rule that reads a pin, a config or a
    -- release and decides ADOPTED or LANDED is its own task, and it writes through these columns.
    state varchar(32) not null,

    -- HOW THIS CONSUMER TAKES THE VERSION, which decides what "adopted" even means for it:
    --
    --   * LINKED            — an ordinary manifest pin. There is a line to edit (a pom property, a
    --                         package.json range, a Dockerfile FROM), the bump machinery already
    --                         knows how, and adoption is that line moving.
    --   * CONFIG_IMAGE_PIN  — the released thing is an IMAGE and the consumer is an application
    --                         whose deployment configuration names it. Nothing in any repository
    --                         this service scans holds that pin; it lives in qits-configuration's
    --                         ImagePins map, and it is READ OVER HTTP. See the note on why those
    --                         nodes are absent at spawn.
    --   * DAEMON_PIN        — the released thing is a DAEMON and the consumer is the service that
    --                         hands it out. Again not a manifest line: the adopting service records
    --                         which daemon build it distributes.
    --
    -- The three are separate END KINDS rather than a flag because one consumer can hold two of them
    -- at once, and they settle independently — hence the unique key below is (train, consumer, end
    -- kind) and not (train, consumer).
    --
    -- CONFIG_IMAGE_PIN NODES ARE DELIBERATELY MISSING AT SPAWN, and that is not an oversight to be
    -- fixed by making the spawn smarter. A spawn runs inside the bus's CLAIM transaction: the
    -- library has written the claim row and not committed it, and this listener's whole failure
    -- policy depends on that transaction being short. Reading qits-configuration's ImagePins over
    -- HTTP from inside it is the same mistake V3 refused for SBOM documents — a slow peer becomes an
    -- event redelivered for ever, with the consumer's watermark stuck behind it. So the train of an
    -- IMAGE repository is spawned with its LINKED nodes and NOTHING for the config side, and the
    -- config-pin sweep materialises these rows on its first pass afterwards, through the same
    -- idempotent (train, consumer, end kind) key everything else here writes through.
    end_kind varchar(32) not null,

    -- WHAT THE CONSUMER ACTUALLY TOOK. Usually the train's own version, and not always: a consumer
    -- can skip a release and adopt the one after it, in which case the node that closes is this
    -- train's and the version recorded is the newer one. Null while PENDING.
    adopted_version varchar(255),

    adopted_at timestamp(6) with time zone,

    -- THE ADOPTION'S OWN TRAIN, when the adoption produced a release of its own — a library takes
    -- the new version, releases, and that release has adopters of its own. This is the link that
    -- makes the estate's journey a graph rather than fifty unrelated rows, and it is what a UI walks
    -- to answer "how far has this actually got".
    --
    -- FLAT, no foreign key, for superseded_by's reason: it points at a peer record, not at a part of
    -- this one.
    child_train_id uuid,

    landed_at timestamp(6) with time zone,

    primary key (id)
);

-- ONE NODE PER (TRAIN, CONSUMER, END KIND), which is the key every writer here is idempotent
-- against: the spawn, a sibling artifact frame that re-derives an empty train, and the config-pin
-- sweep placing rows the spawn could not. Deliberately NOT (train, consumer): one repository can owe
-- a train both a manifest bump and a config pin, and closing one must not close the other.
create unique index uq_mt_train_node_end on mt_train_node (train_id, consumer, end_kind);

-- "The nodes of this train", which is every read the detail view and the completion check make.
create index idx_mt_train_node_train on mt_train_node (train_id);

-- AND THE REVERSE ONE: "which trains is this repository still holding up", across every open train
-- of every repository. It is the listing a person actually wants — what do I owe the estate — and
-- without it that question is a sequential scan of the whole log.
create index idx_mt_train_node_consumer on mt_train_node (consumer, state);
