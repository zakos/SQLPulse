package hu.laurel.sqlpulse.ui

import android.content.Context
import androidx.annotation.StringRes
import hu.laurel.sqlpulse.R
import hu.laurel.sqlpulse.data.sql.SqlFailure
import hu.laurel.sqlpulse.data.sql.SqlFailureKind

/** The sentence that explains a kind of failure, or null when there is nothing to add. */
@StringRes
fun SqlFailureKind.explanationRes(): Int? = when (this) {
    SqlFailureKind.AUTHENTICATION -> R.string.sql_error_authentication
    SqlFailureKind.AUTHENTICATION_UNPROTECTED -> R.string.sql_error_authentication_unprotected
    SqlFailureKind.PRIVILEGE -> R.string.sql_error_privilege
    SqlFailureKind.UNKNOWN_DATABASE -> R.string.sql_error_unknown_database
    SqlFailureKind.UNKNOWN_OBJECT -> R.string.sql_error_unknown_object
    SqlFailureKind.SYNTAX -> R.string.sql_error_syntax
    SqlFailureKind.TLS -> R.string.sql_error_tls
    SqlFailureKind.UNREACHABLE -> R.string.sql_error_unreachable
    SqlFailureKind.TIMEOUT -> R.string.sql_error_timeout
    SqlFailureKind.CONNECTION_LOST -> R.string.sql_error_connection_lost
    SqlFailureKind.LOCK -> R.string.sql_error_lock
    SqlFailureKind.DUPLICATE_KEY -> R.string.sql_error_duplicate_key
    SqlFailureKind.SERVER_BUSY_OR_READ_ONLY -> R.string.sql_error_server_busy
    SqlFailureKind.OTHER -> null
}

/**
 * The explanation followed by the server's own sentence (§11).
 *
 * Both, never one or the other: the explanation says what to do, and the server's wording is what
 * a colleague or a search engine will recognise.
 */
fun Context.explain(failure: SqlFailure): String {
    val server = failure.serverMessage.ifBlank {
        if (failure.errorCode != 0) getString(R.string.sql_error_code, failure.errorCode) else ""
    }
    val explanation = failure.kind.explanationRes()?.let { getString(it) }
    return listOfNotNull(explanation, server.takeIf { it.isNotBlank() }).joinToString("\n\n")
}
