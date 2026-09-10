-- THE DISPATCH WINDOW, AS A ROW, BECAUSE THIS SERVICE RESTARTS ITSELF IN THE MIDDLE OF ONE.
--
-- The gated nightly bump splits one decision in two: the 02:00 cron opens a WINDOW, and a 15s tick
-- hands out one bump at a time while it is open. The window was first written as an
-- `AtomicReference<Instant>` on `BumpDispatcher`, with a javadoc paragraph arguing that losing it to
-- a restart was the cheap correct answer — a process that is down at 02:00 misses the night today
-- too, and a table would be a schema change to remember something the next cron re-derives.
--
-- That argument is wrong here, and it is wrong for a reason particular to THIS service: qits-
-- maintenance is one of the fifty repositories qits-maintenance bumps. Measured live on 2026-09-10:
--
--   06:32 .. 08:10:54   nineteen bumps dispatched, one at a time, bottom of the chain, no waste
--   08:01:06            the bump of `qits-maintenance-platform-service` itself SUCCEEDED
--   08:11:13            its release deployed — the container is replaced, the field dies with it
--   08:11 .. 09:04      nothing dispatched at all, with eleven repositories still owed a bump
--
-- The window had four hours left to run and there is no cron until 02:00 the next night. So the
-- estate stopped mid-chain, which is the exact complaint the gate was built to answer, arriving by
-- a new road. A restart mid-window is not the rare accident the original note assumed: it is the
-- ORDINARY OUTCOME of the window doing its job, because a successful bump of this repository
-- becomes a release, and a release becomes a redeploy of this container.
--
-- ONE ROW, KEYED BY A NAME RATHER THAN A UUID. There is exactly one internal dispatch window and
-- the key is the constant `internal`, so an open is an upsert and cannot fork into two windows —
-- the primary key IS the singleton constraint, and no `select ... limit 1` has to stand in for one.
-- A second kind of window (an external group that learned to bump itself, an environment split)
-- would be a second key here rather than a second table.
--
-- ABSENCE IS "NO WINDOW", so closing DELETES the row rather than nulling a column. The dispatcher
-- asks one question of this table — "is anything welcome to be sent right now" — and a missing row
-- answers it without a second rule about what a null `closes_at` beside a non-null `opened_at`
-- means.
--
-- `closes_at` STILL EXPIRES. Persisting the window did not make it eternal: the tick compares this
-- instant with now exactly as it compared the field, so a window whose service was down for its
-- whole six hours comes back already over and closes on its first tick.

create table mt_bump_window (
    -- Which window. `internal` is the only one this service opens today; see above.
    id varchar(32) not null,

    -- WHEN THE CRON OPENED IT. Nothing gates on this — it is what makes a resumed window legible in
    -- a log or a support read ("this is the 02:00 window, not one somebody drove by hand at 09:00").
    opened_at timestamp(6) with time zone not null,

    -- WHEN IT ENDS, `opened_at` plus `qits.maintenance.bump.internal.window`. The one fact the gate
    -- reads, and it is stored rather than recomputed so that shortening the setting cannot
    -- retroactively shut a window that is already running.
    closes_at timestamp(6) with time zone not null,

    primary key (id)
);
