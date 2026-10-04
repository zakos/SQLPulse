package hu.laurel.sqlpulse.data.sql.dialect

import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * Throw-away certificates for the TLS tests, valid for a hundred years so nothing expires under
 * them. Generated once with openssl (EC P-256): a CA, a leaf signed by it that names
 * `db.example.test`, `*.wild.test` and 10.1.2.3, a leaf with only a common name
 * (`only-cn.example.test`), and an unrelated CA. No private key is kept anywhere.
 */
object TestCertificates {

    val CA = """
-----BEGIN CERTIFICATE-----
MIIBhjCCASugAwIBAgIUPAQrQixFj+Nbjzp9o0r6pkm4xBEwCgYIKoZIzj0EAwIw
FzEVMBMGA1UEAwwMVW5pdCBUZXN0IENBMCAXDTI2MTAwMzEzMDYwNVoYDzIxMjYw
OTA5MTMwNjA1WjAXMRUwEwYDVQQDDAxVbml0IFRlc3QgQ0EwWTATBgcqhkjOPQIB
BggqhkjOPQMBBwNCAASHgYIp/2fLwLbUYoId7m7Wz7DBZBlkuDooTIojLHzYCMHw
dvk/3/RKbFzIhlYhoEa7V387zmYheOl4KP+5Zidbo1MwUTAdBgNVHQ4EFgQUymOY
mI1qv+gHgLI6p0kvRLOdLPMwHwYDVR0jBBgwFoAUymOYmI1qv+gHgLI6p0kvRLOd
LPMwDwYDVR0TAQH/BAUwAwEB/zAKBggqhkjOPQQDAgNJADBGAiEA/IR3X67ojpiT
3alC1HCYSbCgVKxYzmX3TeBAmRLOX1ACIQCp/C0YjPmtC1GgnLL2nNuShITES/4c
MPYWHtQNHaRD0g==
-----END CERTIFICATE-----
""".trimStart()

    val LEAF = """
-----BEGIN CERTIFICATE-----
MIIBvTCCAWOgAwIBAgIUfB1UNYLWTMZqwGqvrICu1uD+bUkwCgYIKoZIzj0EAwIw
FzEVMBMGA1UEAwwMVW5pdCBUZXN0IENBMCAXDTI2MTAwMzEzMDYwNVoYDzIxMjYw
OTA5MTMwNjA1WjAaMRgwFgYDVQQDDA9kYi5leGFtcGxlLnRlc3QwWTATBgcqhkjO
PQIBBggqhkjOPQMBBwNCAAR5NWqfRar3ywwzZsiRkfZUg5aGt2Odl9pP0X7ZHq+b
dLjvgs/gIe9jd/5+RRQBadC2Mm9LFs/46UxU6ZzU3hv0o4GHMIGEMC0GA1UdEQQm
MCSCD2RiLmV4YW1wbGUudGVzdIILKi53aWxkLnRlc3SHBAoBAgMwEwYDVR0lBAww
CgYIKwYBBQUHAwEwHQYDVR0OBBYEFM/U+yGUSFGBcd3I/GeRjylWHX4lMB8GA1Ud
IwQYMBaAFMpjmJiNar/oB4CyOqdJL0SznSzzMAoGCCqGSM49BAMCA0gAMEUCIQDq
hH0pQ3f1hp6QOo4rrKpWACQZ+j8ZlLVbqE8rzL7ikwIgDV/cM0UBwG7MOTbtO+HA
k0fOzE5/3RipHcCTWorOCDw=
-----END CERTIFICATE-----
""".trimStart()

    val OTHER_CA = """
-----BEGIN CERTIFICATE-----
MIIBfjCCASOgAwIBAgIUBO1FrtoAXla0azEWN/FVzeog0VMwCgYIKoZIzj0EAwIw
EzERMA8GA1UEAwwIT3RoZXIgQ0EwIBcNMjYxMDAzMTMwNjA1WhgPMjEyNjA5MDkx
MzA2MDVaMBMxETAPBgNVBAMMCE90aGVyIENBMFkwEwYHKoZIzj0CAQYIKoZIzj0D
AQcDQgAEJFSAUIHMiNWWHD4CgJRcggm1AVsxSb/sBCgJGXPbKRC4WAsatpPd5qYj
UZaIyXD5zAUk+Ny51p2/MbrNrYUP4aNTMFEwHQYDVR0OBBYEFM0Fkh2LOg/qBOaJ
D/E7P66ywOM3MB8GA1UdIwQYMBaAFM0Fkh2LOg/qBOaJD/E7P66ywOM3MA8GA1Ud
EwEB/wQFMAMBAf8wCgYIKoZIzj0EAwIDSQAwRgIhAKTsTXsUbjh00MPBc/dZWMsy
110S5vpls5yc+iIdljYTAiEAiLfq8+TmizcAjbfkf1bShM1mpNbb5Is+G5unq2NS
9kU=
-----END CERTIFICATE-----
""".trimStart()

    val COMMON_NAME_ONLY = """
-----BEGIN CERTIFICATE-----
MIIBMjCB2QIUfB1UNYLWTMZqwGqvrICu1uD+bUowCgYIKoZIzj0EAwIwFzEVMBMG
A1UEAwwMVW5pdCBUZXN0IENBMCAXDTI2MTAwMzEzMDYwNVoYDzIxMjYwOTA5MTMw
NjA1WjAfMR0wGwYDVQQDDBRvbmx5LWNuLmV4YW1wbGUudGVzdDBZMBMGByqGSM49
AgEGCCqGSM49AwEHA0IABIcRECnWDKZJETdspwNhyxj7I/lScP3FsbPg3sMlNBUO
UKManZhdhGtEu5uEAr6hyBQ/XvpbNoSp/Bc76UEfqdkwCgYIKoZIzj0EAwIDSAAw
RQIgRX5df5hPLZVs0lysx9dfvPaxMXi3UQwoW61WA08Qu4QCIQDuROxMPELZls4G
ZPrepZw3+HiGOwC/e6may095hOpBHw==
-----END CERTIFICATE-----
""".trimStart()

    fun parse(pem: String): X509Certificate =
        CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(pem.toByteArray())) as X509Certificate
}
