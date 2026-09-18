package hu.laurel.sqlpulse.data.connection

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import hu.laurel.sqlpulse.di.IoDispatcher
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** The imported PEM is not a certificate the app recognises. */
class InvalidCertificateException : Exception("not a PEM certificate")

/**
 * Holds the CA certificates used to verify database servers.
 *
 * A CA certificate is public material, so it lives as a plain file in the app's private storage
 * rather than inside the encrypted database: the driver needs a path on disk to read it from.
 */
@Singleton
class CertificateStore @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    private val directory: File
        get() = File(context.filesDir, DIRECTORY).apply { mkdirs() }

    fun pathFor(name: String): String = File(directory, name).absolutePath

    fun list(): List<String> = directory.listFiles()?.map { it.name }?.sorted() ?: emptyList()

    /**
     * Copies a certificate chosen through the file picker into the app's storage.
     *
     * The content is checked for a PEM certificate block first: pointing the driver at a random
     * file would otherwise fail much later, with an error that says nothing about the cause.
     */
    suspend fun import(uri: Uri, name: String): String = withContext(io) {
        val text = context.contentResolver.openInputStream(uri)?.use {
            it.readBytes().toString(Charsets.US_ASCII)
        } ?: throw InvalidCertificateException()
        if (!text.contains(PEM_HEADER)) throw InvalidCertificateException()

        val fileName = safeName(name)
        File(directory, fileName).writeText(text)
        fileName
    }

    fun delete(name: String) {
        runCatching { File(directory, safeName(name)).delete() }
    }

    private fun safeName(name: String): String {
        val cleaned = name.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")
        return (cleaned.takeIf { it.isNotBlank() } ?: "ca").let {
            if (it.endsWith(".pem") || it.endsWith(".crt")) it else "$it.pem"
        }
    }

    private companion object {
        const val DIRECTORY = "ca"
        const val PEM_HEADER = "-----BEGIN CERTIFICATE-----"
    }
}
