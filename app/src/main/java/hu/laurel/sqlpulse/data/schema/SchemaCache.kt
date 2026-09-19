package hu.laurel.sqlpulse.data.schema

/**
 * The offline schema cache, as pure decision logic.
 *
 * The app is useless on the underground: §11 says the schema of a database that has already been
 * browsed should stay readable with no connection, and the specification's own open question
 * (§13) asks what the cache strategy should be. This file is the answer to the parts of that
 * question that are decisions rather than plumbing — when a capture counts as stale, what the
 * screen is allowed to show when there is no session, and what a refresh does to what is already
 * stored.
 *
 * It holds no Android, no Room and no `java.sql` import on purpose, exactly as
 * [hu.laurel.sqlpulse.net.ReconnectPolicy] does: those three are what keep a rule out of the unit
 * tests, and a cache whose staleness rule is untested is a cache that will one day show a table
 * that was dropped last month as though the server had just said so. [SchemaCacheRepository] does
 * the storing; everything below is given the clock rather than reading one, so a test can put the
 * capture wherever in time it needs it.
 *
 * The models here are deliberately the cache's own rather than [SchemaTable] and friends. What is
 * written to disk has to survive the live models being reshaped — a column added to [SchemaTable]
 * for a live-only detail must not silently start meaning something in a row captured months ago —
 * and the repository is the single place where the two vocabularies meet.
 */

/** A table as it was when the schema was last read from the server. */
data class CachedTable(
    val database: String,
    val name: String,
    /** The name of a [TableKind] entry. Stored as text: an enum renamed later must not lose rows. */
    val kind: String,
    val approximateRows: Long?,
    val comment: String?,
    val engine: String?,
    val collation: String?,
    val dataBytes: Long?,
    val indexBytes: Long?,
)

/** One column of a cached table. [position] keeps the server's own ordering, which matters. */
data class CachedColumn(
    val name: String,
    val typeName: String,
    val nullable: Boolean,
    val defaultValue: String?,
    val isPrimaryKey: Boolean,
    val extra: String?,
    val comment: String?,
    val position: Int,
)

data class CachedIndex(
    val name: String,
    val unique: Boolean,
    val columns: List<String>,
    val position: Int,
)

data class CachedForeignKey(
    val constraintName: String,
    val column: String,
    val referencedDatabase: String,
    val referencedTable: String,
    val referencedColumn: String,
)

/** Everything the table page shows about one table, as it was captured. */
data class CachedStructure(
    val columns: List<CachedColumn>,
    val indexes: List<CachedIndex>,
    val foreignKeys: List<CachedForeignKey>,
) {
    val primaryKey: List<String> get() = columns.filter { it.isPrimaryKey }.map { it.name }
}

/** A captured value and the moment it was captured. The moment travels with the value. */
data class SchemaSnapshot<out T>(val value: T, val capturedAt: Long)

/** Where what is on screen came from. Never inferred from whether a list is empty. */
enum class SchemaOrigin { LIVE, CACHED }

/**
 * How old a capture is, in the unit a person would say it in.
 *
 * The screen needs this rather than a formatted string: the string is a translated resource and
 * this file has no resources. The buckets are coarse because the number is a warning, not a
 * measurement — "3 days ago" is the whole message, and "3 days 4 hours" would only invite the
 * reader to trust the fresher-sounding half of it.
 */
enum class CacheAgeUnit { JUST_NOW, MINUTES, HOURS, DAYS }

data class CacheAge(val unit: CacheAgeUnit, val count: Int)

/**
 * When a capture stops being something to show without an apology, and when it stops being worth
 * keeping at all.
 *
 * Stale does not mean hidden. A schema that was read a week ago is still overwhelmingly correct,
 * and hiding it would take the offline browser away exactly when it is needed. Stale means the
 * marker says so more loudly; [expireAfterMillis] is where the row is dropped instead, because
 * past that point the odds of a column having moved are high enough that showing it would be
 * misleading rather than helpful.
 */
data class SchemaCachePolicy(
    val staleAfterMillis: Long = DEFAULT_STALE_AFTER_MILLIS,
    val expireAfterMillis: Long = DEFAULT_EXPIRE_AFTER_MILLIS,
) {
    init {
        require(staleAfterMillis > 0) { "staleAfterMillis must be positive" }
        require(expireAfterMillis >= staleAfterMillis) {
            "a capture cannot expire before it goes stale"
        }
    }

    companion object {
        /** A working day: a schema read this morning is still this morning's schema. */
        const val DEFAULT_STALE_AFTER_MILLIS: Long = 24L * 60 * 60 * 1000

        /** A month. Past this the capture is deleted rather than shown with a bigger warning. */
        const val DEFAULT_EXPIRE_AFTER_MILLIS: Long = 30L * 24 * 60 * 60 * 1000
    }
}

/**
 * What the schema browser should put on screen.
 *
 * [Cached] carries the capture time rather than leaving the screen to look it up, because the one
 * failure this whole feature has to avoid is a cached list rendered as though it were live. The
 * time is part of the value; there is no way to show the list without also having the moment it
 * was taken.
 */
sealed interface SchemaView<out T> {

    /** The server answered. [origin] is [SchemaOrigin.LIVE] and there is nothing to disclaim. */
    data class Live<T>(val value: T) : SchemaView<T> {
        val origin: SchemaOrigin get() = SchemaOrigin.LIVE
    }

    /**
     * No session, and this is what was stored the last time there was one.
     *
     * [stale] only says the capture is older than the policy allows; the data is shown either way
     * and the marker is what changes.
     */
    data class Cached<T>(
        val value: T,
        val capturedAt: Long,
        val age: CacheAge,
        val stale: Boolean,
    ) : SchemaView<T> {
        val origin: SchemaOrigin get() = SchemaOrigin.CACHED
    }

    /** Neither a session nor a capture: the database has never been browsed on this phone. */
    data object Absent : SchemaView<Nothing>
}

/** True only for a live answer. Anything that talks to the server must ask this first. */
val SchemaView<*>.isLive: Boolean get() = this is SchemaView.Live

/** The value to render, or null when there is nothing at all. */
fun <T> SchemaView<T>.valueOrNull(): T? = when (this) {
    is SchemaView.Live -> value
    is SchemaView.Cached -> value
    SchemaView.Absent -> null
}

/**
 * The cache decisions, all of them, in one place.
 *
 * Every function takes `now`. None of them reads a clock, opens a database or knows what a
 * connection is.
 */
object SchemaCache {

    /** Milliseconds since the capture, never negative — a clock that moved back reads as zero. */
    fun ageMillis(capturedAt: Long, now: Long): Long = (now - capturedAt).coerceAtLeast(0)

    /**
     * Older than the policy allows.
     *
     * Exactly at the boundary is not yet stale: a 24-hour policy should call a capture taken
     * 24 hours ago the last fresh one, not the first stale one, so the boundary belongs to the
     * side that does not shout.
     */
    fun isStale(capturedAt: Long, now: Long, policy: SchemaCachePolicy = SchemaCachePolicy()): Boolean =
        ageMillis(capturedAt, now) > policy.staleAfterMillis

    /** Past [SchemaCachePolicy.expireAfterMillis]: the row should be deleted, not shown. */
    fun isExpired(capturedAt: Long, now: Long, policy: SchemaCachePolicy = SchemaCachePolicy()): Boolean =
        ageMillis(capturedAt, now) > policy.expireAfterMillis

    /**
     * The age in the coarsest unit that still says something true.
     *
     * Under a minute is [CacheAgeUnit.JUST_NOW] with no number: a capture taken forty seconds ago
     * is, for the reader's purposes, now, and "0 minutes ago" reads like a bug.
     */
    fun age(capturedAt: Long, now: Long): CacheAge {
        val millis = ageMillis(capturedAt, now)
        val minutes = millis / 60_000
        return when {
            minutes < 1 -> CacheAge(CacheAgeUnit.JUST_NOW, 0)
            minutes < 60 -> CacheAge(CacheAgeUnit.MINUTES, minutes.toInt())
            minutes < 24 * 60 -> CacheAge(CacheAgeUnit.HOURS, (minutes / 60).toInt())
            else -> CacheAge(CacheAgeUnit.DAYS, (minutes / (24 * 60)).toInt())
        }
    }

    /**
     * What to show, given what the server said (or did not) and what is stored.
     *
     * The live answer always wins, even when it is empty: an empty database is a fact about the
     * server, and quietly replacing it with last week's tables would be the exact confusion this
     * feature must not create. A capture is only reached for when there is no live answer at all,
     * and an expired one is treated as though it were not there.
     */
    fun <T> view(
        live: T?,
        cached: SchemaSnapshot<T>?,
        now: Long,
        policy: SchemaCachePolicy = SchemaCachePolicy(),
    ): SchemaView<T> {
        if (live != null) return SchemaView.Live(live)
        if (cached == null || isExpired(cached.capturedAt, now, policy)) return SchemaView.Absent
        return SchemaView.Cached(
            value = cached.value,
            capturedAt = cached.capturedAt,
            age = age(cached.capturedAt, now),
            stale = isStale(cached.capturedAt, now, policy),
        )
    }

    /**
     * Whether a capture should be taken now.
     *
     * Writing the schema to disk on every tree expansion would mean an encrypted write per tap for
     * data that has not changed, so a capture is only refreshed once the stored one has gone
     * stale — or when the caller asks for it outright, which is what the refresh button does.
     */
    fun shouldCapture(
        cachedAt: Long?,
        now: Long,
        userAsked: Boolean = false,
        policy: SchemaCachePolicy = SchemaCachePolicy(),
    ): Boolean = when {
        userAsked -> true
        cachedAt == null -> true
        else -> isStale(cachedAt, now, policy)
    }

    /**
     * What a refresh does to what is stored: which rows are new, which are replaced, and which
     * are gone from the server and must therefore go from the cache too.
     *
     * The deletions are the reason this is a merge rather than a delete-all-and-insert. A wipe
     * followed by an insert leaves the cache empty if the insert fails halfway, and a database is
     * browsed offline precisely when the link is unreliable — so the repository is handed the
     * difference and applies it in one transaction, and a table the server no longer has is
     * removed rather than lingering as a row nobody can open.
     */
    fun <T> merge(
        cached: List<T>,
        fresh: List<T>,
        key: (T) -> String,
    ): CacheMerge<T> {
        val cachedByKey = cached.associateBy(key)
        val freshByKey = fresh.associateBy(key)
        return CacheMerge(
            added = fresh.filter { key(it) !in cachedByKey },
            changed = fresh.filter { item ->
                val previous = cachedByKey[key(item)]
                previous != null && previous != item
            },
            unchanged = fresh.filter { cachedByKey[key(it)] == it },
            removedKeys = cachedByKey.keys.filter { it !in freshByKey },
        )
    }

    /** [merge] for the table list, keyed the way the cache keys it: database and name. */
    fun mergeTables(cached: List<CachedTable>, fresh: List<CachedTable>): CacheMerge<CachedTable> =
        merge(cached, fresh) { "${it.database}.${it.name}" }

    /**
     * An index's columns as one text value, and back.
     *
     * A column name may contain almost anything, including a comma, so the separator is a
     * character that cannot appear in a MySQL identifier at all. Splitting an empty string would
     * otherwise yield one empty column, which is how an index with no columns would come back as
     * an index on a column called "".
     */
    fun joinColumns(columns: List<String>): String = columns.joinToString(COLUMN_SEPARATOR)

    fun splitColumns(stored: String): List<String> =
        if (stored.isEmpty()) emptyList() else stored.split(COLUMN_SEPARATOR)

    /** A unit separator: MySQL identifiers cannot contain it, so it can never be part of a name. */
    private const val COLUMN_SEPARATOR = "\u001F"
}

/**
 * The difference a refresh makes, as data the repository applies.
 *
 * [unchanged] is carried rather than dropped so a caller can tell "the server said the same
 * thing" from "the server said nothing", which is the difference between a capture that should
 * have its timestamp moved forward and one that should not.
 */
data class CacheMerge<T>(
    val added: List<T>,
    val changed: List<T>,
    val unchanged: List<T>,
    val removedKeys: List<String>,
) {
    /** Everything that should be written back, new rows and replaced rows alike. */
    val upserts: List<T> get() = added + changed

    /** True when the refresh found the schema exactly as it was stored. */
    val identical: Boolean get() = added.isEmpty() && changed.isEmpty() && removedKeys.isEmpty()
}
