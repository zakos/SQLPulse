package hu.laurel.sqlpulse.data.backup

/** Fixtures shared by the backup tests. */
object BackupSamples {

    fun key(name: String = "laptop", withMaterial: Boolean = false) = BackupSshKey(
        name = name,
        algorithm = "ED25519",
        bits = 256,
        fingerprint = "SHA256:abcdef$name",
        publicKey = "ssh-ed25519 AAAAC3Nz sqlpulse-$name",
        createdAt = 1_700_000_000_000L,
        materialFormat = "ED25519_SEED",
        privateKeyBase64 = if (withMaterial) "AAECAwQFBgcICQoLDA0ODw==" else null,
    )

    fun connection(
        name: String = "reports",
        withSecrets: Boolean = false,
        keyFingerprint: String? = "SHA256:abcdeflaptop",
    ) = BackupConnection(
        name = name,
        color = "Teal",
        useSshTunnel = true,
        sshHost = "bastion.example.com",
        sshPort = 2222,
        sshUser = "deploy",
        sshAuthMethod = "KEY",
        sshKeyFingerprint = keyFingerprint,
        sshJumpHost = "edge.example.com",
        sshJumpPort = 22,
        sshJumpUser = "jump",
        sshJumpAuthMethod = "PASSWORD",
        sshJumpKeyFingerprint = null,
        dbHost = "127.0.0.1",
        dbPort = 3306,
        database = "sales",
        dbUser = "readonly",
        readOnly = true,
        environment = "PRODUCTION",
        connectTimeoutSeconds = 15,
        queryTimeoutSeconds = 45,
        sslMode = "VERIFY_CA",
        caCertificate = "corp-ca.pem",
        savedQueries = listOf(
            BackupSavedQuery("today", "SELECT * FROM orders WHERE day = :day", "day"),
            BackupSavedQuery("counts", "SELECT COUNT(*) FROM orders", ""),
        ),
        dbPassword = if (withSecrets) "hunter2-db" else null,
        sshPassword = if (withSecrets) "hunter2-ssh" else null,
        jumpSshPassword = if (withSecrets) "hunter2-jump" else null,
    )

    fun payload(withSecrets: Boolean = false) = BackupPayload(
        connections = listOf(
            connection("reports", withSecrets),
            connection("staging", withSecrets).copy(environment = "TEST", savedQueries = emptyList()),
        ),
        sshKeys = listOf(key("laptop", withSecrets), key("phone", withSecrets)),
        certificates = listOf(
            BackupCertificate("corp-ca.pem", "-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----\n"),
        ),
    )
}
