-- THE BUMP ASKS FOR ITS OWN RELEASE NOW. A bump that ends SUCCEEDED — a green run whose branch head
-- moved — calls qits-workspaces' release door itself, and this column is what it remembers of the
-- answer. Until now the ask came from a per-repository CI trigger
-- (.config/qits/ci-event-maintenance-release.yml) firing on the same push; that trigger stays for the
-- length of the rollout, and the door converges rather than duplicating, so both asking is safe.
--
-- WHAT THE COLUMN HOLDS IS EITHER AN ID OR ONE OF TWO WORDS, and the words are load-bearing:
--
--   * a request id — the door created (or converged onto) a release request in qits-projects, and
--     this is the row to poll over there;
--   * 'converged'  — there was nothing to ask for. The door said the branch is already integrated,
--                    or the branch reached RELEASED / vanished before the ask could be made;
--   * 'refused'    — the door refused in a way that retrying cannot fix (a 400 or a 404). The
--                    sentence on `message` says which.
--
-- NULL IS THE ONE STATE THAT MEANS WORK IS OWED, and that is the whole reason a sentinel exists
-- rather than a second boolean. The sweep re-attempts the door for exactly the rows that are
-- SUCCEEDED with this column null and whose branch is still PUSHED — so a door that was down when
-- the bump finished is retried on the next tick, and every ending writes something here and stops
-- being read. Without the words, a permanently refused ask would be re-sent every fifteen seconds for
-- the life of the branch.
--
-- IT NEVER CHANGES THE BUMP'S STATUS. The bump SUCCEEDED: the run was green and the branch moved,
-- which is a fact about this service's own work and not about whether a fourth peer answered. A door
-- outage that flipped a bump to FAILED would be this service reporting somebody else's downtime as
-- its own failure, and the branch really is pushed.
alter table mt_bump add column release_request_id varchar(255);

-- EVERY BUMP THAT ALREADY SUCCEEDED IS SETTLED, whatever became of its branch. Their branches were
-- released, drained or abandoned under the old lifecycle — before this column existed and before
-- anything here called the door — so leaving them null would have the first sweep after this deploy
-- ask the door about every historical branch at once. 'converged' is the honest word for it: nothing
-- is owed, and the id is absent because no ask was ever made from here.
update mt_bump set release_request_id = 'converged' where status = 'SUCCEEDED';

-- The sweep's read: `where status = 'SUCCEEDED' and release_request_id is null`. Partial, because the
-- rows it wants are the transient few and the table is a log that only grows.
create index idx_mt_bump_release_owed on mt_bump (status) where release_request_id is null;
