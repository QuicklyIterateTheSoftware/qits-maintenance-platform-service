-- THE INTERNAL/EXTERNAL SPLIT. Until now every repository fell to ONE catch-all group,
-- `dependencies` with the pattern `*`, so a nightly bump of the platform's own releases and an
-- upgrade of somebody else's framework arrived on the same branch, in the same review. The default
-- grouping is now the pin's KIND: `dependencies` claims every INTERNAL pin and a new `external`
-- group claims every EXTERNAL one, on `maintenance/external`.
--
-- A GROUP NOW CLAIMS ONE OF TWO WAYS. `kind` set means it takes every pin of that kind and its
-- `patterns` are `[]`, read by nothing; `kind` null means the globs decide, exactly as before. The
-- glob mechanism is not replaced — it is how a repository asks for a finer grouping than the two
-- halves, and its groups are still tried BEFORE the kind pair.
--
-- WHY THE ROWS ARE REWRITTEN HERE RATHER THAN LEFT TO THE NEXT SCAN. mt_group is a cache and the
-- next scan would rewrite it wholesale — but "the next scan" is up to six hours away, and between
-- this boot and it every page would show the old single group and every bump would compose the old
-- mixed payload. The rewrite makes the split true from the first request after the deploy.
--
-- WHAT IS AND IS NOT TOUCHED:
--   * mt_group DEFAULT rows are a CACHE of a file that does not exist, so they are simply replaced.
--   * mt_group CONFIG rows do not exist today — no repository on the platform carries
--     .config/qits/maintenance.yml — and are deliberately left alone if one ever does: its own
--     groups keep their ordinals and it gets its kind tail from the next scan.
--   * mt_branch rows for (repository, 'dependencies') KEEP THEIR MEANING. That group is now the
--     INTERNAL half rather than everything, but its name and its branch are unchanged, so the row
--     still describes the branch the next internal bump writes. The `external` group mints its own
--     branch row on its first bump.
--   * mt_bump is a LOG keyed by the branch strings it sent. Nothing is rewritten there: a past bump
--     really did put both halves on maintenance/dependencies, and a row that said otherwise would
--     be a lie about a branch somebody can still read.
--
-- THE OPERATIONAL CUTOVER, which no migration can do. Every `maintenance/dependencies` branch that
-- exists right now carries MIXED commits — internal and external together, from before the split.
-- Drain them (release or delete them) before nightly bumps are enabled again, or the first internal
-- bump after this deploy pushes onto a branch that still carries an external upgrade nobody
-- reviewed under that name.

alter table mt_group add column kind varchar(32);

-- The old catch-all rows, every one of them a DEFAULT-source cache row.
delete from mt_group where source = 'DEFAULT';

-- …and the pair that replaces them, for every repository the delete emptied. A repository whose
-- groups survived the delete is a CONFIG one and is left to its own file; a repository that has
-- never been scanned has no groups either way and gets the pair with no pins to claim, which the
-- next scan overwrites. `gen_random_uuid()` is postgres' own since 13 — the ids are app-generated
-- everywhere else, and this is the one place no application is writing the row.
insert into mt_group (id, repository, name, ordinal, patterns, source, kind)
select
    gen_random_uuid(),
    repository.name,
    tail.name,
    tail.ordinal,
    '[]',
    'DEFAULT',
    tail.kind
from mt_repository repository
cross join (values ('dependencies', 0, 'INTERNAL'), ('external', 1, 'EXTERNAL'))
        as tail (name, ordinal, kind)
where not exists (select 1 from mt_group existing where existing.repository = repository.name);
