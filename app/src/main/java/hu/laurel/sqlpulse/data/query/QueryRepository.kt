package hu.laurel.sqlpulse.data.query

import hu.laurel.sqlpulse.data.db.QueryHistoryDao
import hu.laurel.sqlpulse.data.db.QueryHistoryEntity
import hu.laurel.sqlpulse.data.db.SavedQueryDao
import hu.laurel.sqlpulse.data.db.SavedQueryEntity
import hu.laurel.sqlpulse.data.sql.SqlGuards
import hu.laurel.sqlpulse.di.IoDispatcher
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/** History and favourites (§7.4, §9). Both live in the encrypted local database. */
@Singleton
class QueryRepository @Inject constructor(
    private val historyDao: QueryHistoryDao,
    private val savedDao: SavedQueryDao,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    fun observeHistory(connectionId: Long): Flow<List<QueryHistoryEntity>> =
        historyDao.observeRecent(connectionId, HISTORY_PAGE)

    fun observeFavourites(connectionId: Long): Flow<List<SavedQueryEntity>> =
        savedDao.observeAll(connectionId)

    /** Saves a query as a favourite, recording the `:parameter` names it needs. */
    suspend fun saveFavourite(connectionId: Long, name: String, sql: String): SavedQueryEntity {
        val entity = SavedQueryEntity(
            connectionId = connectionId,
            name = name.trim().ifEmpty { sql.take(FALLBACK_NAME_LENGTH) },
            sql = sql.trim(),
            parameters = SqlGuards.parameters(sql).joinToString(","),
        )
        withContext(io) { savedDao.upsert(entity) }
        return entity
    }

    suspend fun deleteFavourite(id: Long) = withContext(io) { savedDao.delete(id) }

    private companion object {
        const val HISTORY_PAGE = 200
        const val FALLBACK_NAME_LENGTH = 40
    }
}
