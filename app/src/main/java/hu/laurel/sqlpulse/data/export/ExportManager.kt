package hu.laurel.sqlpulse.data.export

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import hu.laurel.sqlpulse.data.sql.ResultTable
import hu.laurel.sqlpulse.di.IoDispatcher
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Hands an exported result to the system share sheet (§7.7).
 *
 * The app keeps no export files: the only copy is a temporary one in the cache directory, shared
 * through a FileProvider and wiped on the next start and on every lock.
 */
@Singleton
class ExportManager @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /**
     * @param tableName the table INSERT statements should name; null lets the serializer fall back
     *   to what the driver said the columns came from. The file's name is not it: "query" is a
     *   good file name and a terrible table name.
     */
    suspend fun shareIntent(
        table: ResultTable,
        format: ExportFormat,
        baseName: String,
        tableName: String? = null,
    ): Intent = withContext(io) {
        val directory = File(context.cacheDir, EXPORT_DIRECTORY).apply { mkdirs() }
        val file = File(directory, "${safeName(baseName)}-${System.currentTimeMillis()}.${format.extension}")
        file.writeText(ResultSerializer.serialize(table, format, tableName))

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.exports", file)
        Intent(Intent.ACTION_SEND).apply {
            type = format.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /** Called at start and whenever the app locks, so exports never outlive the session (§9). */
    fun clearExports() {
        runCatching { File(context.cacheDir, EXPORT_DIRECTORY).deleteRecursively() }
    }

    private fun safeName(name: String): String =
        name.ifBlank { "export" }.replace(Regex("[^A-Za-z0-9_-]"), "_").take(MAX_NAME_LENGTH)

    private companion object {
        const val EXPORT_DIRECTORY = "exports"
        const val MAX_NAME_LENGTH = 40
    }
}
