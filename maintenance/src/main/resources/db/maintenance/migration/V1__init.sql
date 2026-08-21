-- The whole schema of qits-platform-maintenance, in one migration.
--
-- One V1 and no inherited lineage: this repository starts on PostgreSQL and has never had another
-- store. From here on the ordinary rule holds — keep appending, never edit an applied migration.
--
-- SIX TABLES, AND FIVE OF THEM ARE AN INVENTORY. `mt_repository`, `mt_pin`, `mt_group` and
-- `mt_latest` are what a scan writes: what every repository pins today, how it wants those pins
-- grouped, and what the newest published version of each dependency is. They are a CACHE of other
-- people's truth — a git host's files and a registry's metadata — and a fresh scan replaces them
-- wholesale.
--
-- `mt_branch` and `mt_bump` are the other kind: a LOG of what this service asked qits-ci to do and
-- what came back. Those rows are not derivable from anything and are never replaced.
--
-- PENDING IS NOT A TABLE. "which pins are behind" is `mt_pin` joined to `mt_latest` and read
-- through `mt_group`, computed on every read. Storing it would be a third copy of a fact that two
-- other tables already carry, going stale between the scan that moved a pin and the scan that
-- moved a latest.
--
-- NO FOREIGN KEY TO ANY OTHER CONTEXT, and there will not be one: a repository name, a project, a
-- dependency name and a CI run id are strings that belong to another service's database.

create table mt_repository (
    -- The catalog name, from qits-projects' repository listing. It IS the identity: every other
    -- read this service makes is name-addressed, so a surrogate key would only be a second name.
    name varchar(255) not null,

    -- Which project the git host serves it under. Part of the clone coordinate
    -- (/git/<project>/<repo>) and read back at scan time, so a repository that moves project is
    -- corrected by the next scan rather than by a migration.
    project varchar(255),

    -- The branch a scan reads and a bump branches from, as qits-projects records it. Read back at
    -- scan time so a repository that renames its trunk is corrected by the next scan.
    main_branch varchar(255),

    -- When the last scan finished, whatever its outcome. Null means never scanned.
    last_scan_at timestamp(6) with time zone,

    -- The commit the pins below were read at. ONE sha per scan: every manifest of one repository is
    -- read at the same revision, so the inventory is a snapshot of a tree rather than a mixture of
    -- moments.
    head_sha varchar(64),

    -- OK, ABSENT (the git host has no such repository), UNREACHABLE (it could not be asked) or
    -- CONFIG_ERROR (.config/qits/maintenance.yml does not parse). Not a check constraint: the
    -- vocabulary is the scanner's, in code, and a historical row must keep the word it was written
    -- with.
    status varchar(32) not null,

    -- Why the status is not OK, in one sentence, for the UI to show. Null when it is.
    message text,

    primary key (name)
);

create table mt_pin (
    id uuid not null,

    -- The repository the pin was read from. No FK: a scan rewrites the pins of one repository in
    -- one transaction, and a cascade is not a behaviour this table wants.
    repository varchar(255) not null,

    -- The manifest it was read from, relative to the repository root: `pom.xml`,
    -- `service/pom.xml`, `package.json`, `Dockerfile`. A repository has many, and "who pins X" has
    -- to answer with the file as well as the repository.
    manifest_path varchar(1024) not null,

    -- maven, npm or docker. It decides which registry answers "what is the latest", and which of
    -- the two bump steps can edit this line.
    ecosystem varchar(32) not null,

    -- The dependency's name in its own ecosystem's spelling: `groupId:artifactId` for maven,
    -- `@scope/name` for npm, the image name for docker. It is what a group's globs match and what
    -- joins to mt_latest.
    name varchar(512) not null,

    -- The version that is pinned RIGHT NOW. For npm this is the LOCK's resolved version, not the
    -- range — the range is what the manifest says, the version is what an install would get.
    version varchar(255) not null,

    -- The npm range from package.json (`^21.0.0`), kept beside the resolved version so a bump can
    -- be judged against what the author allowed. Null for maven and docker, which pin exactly.
    range varchar(255),

    -- INTERNAL (this platform publishes it) or EXTERNAL. A name rule, configured — maven groups,
    -- npm scopes, image prefixes — and it decides which registry is asked and which scan schedule
    -- refreshes it.
    kind varchar(32) not null,

    -- WHERE THE VERSION IS SET, so the bump step edits the right line rather than searching for the
    -- string. `property:qits.eventstream.version` or `dependency:g:a` for maven, `dependencies` /
    -- `devDependencies` for npm, `line:<n>` for docker.
    location varchar(512) not null,

    primary key (id)
);

-- The two reads this table has: "the pins of this repository" (the detail page and the pending
-- computation) and "who pins this dependency" (the dependency page).
create index idx_mt_pin_repository on mt_pin (repository);
create index idx_mt_pin_name on mt_pin (ecosystem, name);

create table mt_latest (
    id uuid not null,

    -- maven, npm or docker — the same vocabulary mt_pin uses, because the pair is the join key.
    ecosystem varchar(32) not null,

    name varchar(512) not null,

    -- The newest version the registry offers, by that ecosystem's own order. Null when the lookup
    -- failed: the row is still written, because "we asked and could not find out" is what the UI
    -- has to show instead of silently reporting nothing pending.
    latest varchar(255),

    checked_at timestamp(6) with time zone not null,

    -- The url that was read, so a wrong answer can be reproduced by hand.
    source_url text,

    -- Why there is no latest. Null when the lookup succeeded.
    error text,

    primary key (id)
);

-- One row per dependency, and the join in both directions goes through this pair.
create unique index uq_mt_latest_name on mt_latest (ecosystem, name);

create table mt_group (
    id uuid not null,

    repository varchar(255) not null,

    -- The group's name, which is also the branch suffix: `dependencies` becomes
    -- `maintenance/dependencies`.
    name varchar(255) not null,

    -- THE ORDER THE GROUPS WERE DECLARED IN, from 0. A pin matching two groups belongs to the
    -- FIRST, so the declaration order in `.config/qits/maintenance.yml` is part of the meaning and
    -- has to survive the round trip through this table. Without it "first match" would be whatever
    -- order the database returned rows in.
    ordinal integer not null,

    -- The globs, as a JSON array of strings. A json column rather than a child table: the patterns
    -- are read as a whole, written as a whole, and never queried by.
    patterns text not null,

    -- CONFIG (the repository carries .config/qits/maintenance.yml) or DEFAULT (it does not, so it
    -- gets the one catch-all group). It is what tells an operator whether a repository has opted
    -- into grouping or is simply on the fallback.
    source varchar(32) not null,

    primary key (id)
);

create unique index uq_mt_group_repository_name on mt_group (repository, name);

create table mt_branch (
    id uuid not null,

    repository varchar(255) not null,

    -- The group this branch carries. Together with the repository it is the branch's identity.
    group_name varchar(255) not null,

    -- The full ref name without refs/heads/: `maintenance/dependencies`.
    branch varchar(512) not null,

    -- NONE (never pushed, or deleted by the release door's cleanup), PUSHED (it exists and this
    -- service put it there), STALE (someone rewrote it by hand and the ff-only push was rejected —
    -- they own it now), RELEASED (it went through the release door) or FAILED (the bump run went
    -- red).
    state varchar(32) not null,

    -- The branch head as last read from the git host. It is what "did the bump actually change
    -- anything" is answered with: the head is read before the bump is dispatched and again when the
    -- run ends, and an unmoved head is NOTHING_TO_DO rather than success.
    head_sha varchar(64),

    updated_at timestamp(6) with time zone not null,

    primary key (id)
);

create unique index uq_mt_branch_repository_group on mt_branch (repository, group_name);

create table mt_bump (
    -- ALSO THE CI EVENT'S DEDUPE KEY. It goes out as `eventId` in the trigger, so a retry of a
    -- dispatch that already reached qits-ci records no second run there.
    id uuid not null,

    repository varchar(255) not null,

    group_name varchar(255) not null,

    -- WHICH ENVIRONMENT'S CI RAN IT. This service is platform tier and CI is per environment; v1
    -- talks to one, and recording the name here is what makes a second environment a config entry
    -- rather than a schema change.
    environment varchar(64) not null,

    -- SCHEDULED (a scan found pending changes and bump.auto is on) or MANUAL (the button).
    trigger varchar(32) not null,

    -- The `eventId` this row sent, which is the id above as text. Stored rather than derived so a
    -- row stays readable if the dedupe key ever stops being the row id.
    ci_event_id varchar(255),

    -- The run ids qits-ci answered the trigger with, comma-separated. Plural because a trigger can
    -- match more than one pipeline; v1 declares one, and the poller follows every id it was given
    -- rather than assuming the count.
    ci_run_id text,

    -- The last CI run status this service read, verbatim. It is what `GET /bumps/{id}` reports
    -- beside the bump's own status, so a RUNNING bump can say what CI is doing.
    ci_run_status varchar(32),

    -- REQUESTED (queued, or dispatch deferred because qits-ci answered 503), RUNNING (CI accepted
    -- it), SUCCEEDED (the run passed and the branch moved), FAILED, or NOTHING_TO_DO (the run
    -- passed and the branch head did not move — there was nothing to write).
    status varchar(32) not null,

    -- The changes that were SENT, as the JSON array the trigger payload carried. Stored rather than
    -- recomputed: by the time anyone reads this row the pins have moved, and "what did we ask for"
    -- is the question a surprising branch is investigated with.
    changes text,

    started_at timestamp(6) with time zone not null,

    -- Null while the bump is REQUESTED or RUNNING, and null forever for a bump whose process died
    -- mid-flight. Deliberately not backfilled at boot: a successor knows nothing about what the
    -- dead one's CI run did.
    finished_at timestamp(6) with time zone,

    message text,

    primary key (id)
);

-- The bump listing is `where repository = ? order by started_at desc limit ?`, and the active-bump
-- check is `where repository = ? and group_name = ? and status in (...)`. Both are this index.
create index idx_mt_bump_repository_started_at on mt_bump (repository, started_at desc);
create index idx_mt_bump_status on mt_bump (status);
