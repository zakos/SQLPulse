# sshj reflects over its transport/cipher factories.
-keep class net.schmizz.sshj.** { *; }
-keep class com.hierynomus.** { *; }
-dontwarn net.schmizz.sshj.**
-dontwarn com.hierynomus.**

# Bouncy Castle providers are looked up by name.
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.jce.provider.** { *; }
-dontwarn org.bouncycastle.**

# EdDSA provider.
-keep class net.i2p.crypto.eddsa.** { *; }

# MariaDB Connector/J loads its driver and plugins by service lookup.
-keep class org.mariadb.jdbc.** { *; }
-keepnames class org.mariadb.jdbc.Driver
-dontwarn org.mariadb.jdbc.**
-dontwarn com.github.waffle.**
-dontwarn javax.naming.**

# SQLCipher native bindings.
-keep class net.zetetic.database.** { *; }

# sshj compiles against JDK/servlet APIs that are absent on Android.
-dontwarn java.awt.**
-dontwarn javax.annotation.**
-dontwarn org.slf4j.**

# ---------------------------------------------------------------------------------------------
# Release (R8) rules. Everything below is here because something looks a class, a method or a
# field up by name at runtime, which R8 cannot see and would otherwise rename or remove.
# ---------------------------------------------------------------------------------------------

# Generic signatures, annotations and inner-class links are read reflectively by Room's generated
# code, by Hilt and by both JDBC drivers; without them a release build fails at the first query
# rather than at compile time.
-keepattributes Signature, InnerClasses, EnclosingMethod, Exceptions, *Annotation*

# Both drivers are instantiated by name in SqlSession and also publish themselves through
# META-INF/services, whose entries are strings R8 does not rewrite.
-keep class * implements java.sql.Driver

# MySQL Connector/J 5.1, the legacy driver, used only for pre-5.5.3 servers. It builds the names
# of its socket factory, authentication plugins and Connection implementation at runtime and
# loads them with Class.forName, so nothing of it can be renamed or dropped.
-keep class com.mysql.jdbc.** { *; }
-keepnames class com.mysql.jdbc.Driver
-dontwarn com.mysql.jdbc.**
# It also compiles against server-side and JDK-only APIs that Android does not ship.
-dontwarn com.mysql.**
-dontwarn javax.management.**
-dontwarn org.apache.log4j.**
-dontwarn org.apache.commons.logging.**
-dontwarn java.util.jmx.**

# Room instantiates <Database>_Impl through Class.forName built from the abstract class's own
# name, so both names have to survive; the generated class needs its no-argument constructor.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keepnames class * extends androidx.room.RoomDatabase
# The entities and DAOs themselves are referenced from that generated code by name as well.
-keep @androidx.room.Entity class hu.laurel.sqlpulse.data.db.** { *; }
-keep @androidx.room.Dao interface hu.laurel.sqlpulse.data.db.** { *; }
-keep class androidx.room.RoomDatabase$JournalMode { *; }

# SQLCipher: the Java side is bound to libsqlcipher.so by JNI, which resolves both the native
# methods and the classes they hand back by name. A rename here fails at the first open, on the
# device, with the database already encrypted.
-keep class net.zetetic.database.** { *; }
-keepclasseswithmembernames class * { native <methods>; }
# Room reaches SQLCipher through the support-SQLite interfaces, which its factory implements.
-keep class androidx.sqlite.db.** { *; }

# sshj's Ed25519 implementation (net.i2p.crypto:eddsa) asks whether a public key is a
# sun.security.x509.X509Key — a JDK-internal class Android has never shipped. The branch is dead
# here: on a desktop JVM it is one way of unwrapping a key, and on Android the key always arrives
# as one of the other shapes. R8 treats a missing class as an error, so it has to be told that
# this one is expected to be absent rather than left out by mistake.
-dontwarn sun.security.x509.**


# ---------------------------------------------------------------------------------------------
# Further engines (docs/tobb-motor-terv.md). Both drivers are kept whole for the same reason the
# MariaDB one is: they load their socket/SSL factories, authentication plugins and message bundles
# by class name, which R8 cannot follow.
# ---------------------------------------------------------------------------------------------

# PostgreSQL (pgjdbc). Everything it names but Android lacks belongs to optional features this app
# never switches on: Windows SSPI (waffle/JNA), Kerberos (GSS), OSGi registration, XA, and the
# checker-framework annotations its sources are compiled with.
-keep class org.postgresql.** { *; }
-dontwarn org.postgresql.**
-dontwarn waffle.windows.auth.**
-dontwarn com.sun.jna.**
-dontwarn org.ietf.jgss.**
-dontwarn org.osgi.**
-dontwarn javax.transaction.xa.**
-dontwarn javax.sql.XAConnection
-dontwarn javax.sql.XADataSource
-dontwarn org.checkerframework.**

# SQL Server (Microsoft mssql-jdbc). The missing classes are its optional Azure AD / Key Vault
# stack (msal4j, azure-*, reactor, gson), the ANTLR parser of Always Encrypted, Kerberos/JAAS,
# SQLXML's StAX, and three JDK-only pieces: JDBC 4.2's JDBCType/SQLType (only reached through
# setObject(…, SQLType), which this app never calls), java.lang.management (only for a
# percentage-valued maxResultBuffer, which is never set) and java.beans.Transient (an annotation).
-keep class com.microsoft.sqlserver.** { *; }
-keep class microsoft.sql.** { *; }
# The CA trust manager the driver loads by name (trustManagerClass) with one String argument.
-keep class hu.laurel.sqlpulse.data.sql.dialect.PemTrustManager { <init>(java.lang.String); }
-dontwarn com.microsoft.sqlserver.**
-dontwarn com.microsoft.aad.msal4j.**
-dontwarn com.azure.**
-dontwarn reactor.core.**
-dontwarn com.google.gson.**
-dontwarn org.antlr.v4.**
-dontwarn javax.security.auth.**
-dontwarn javax.xml.stream.**
-dontwarn javax.xml.transform.stax.**
-dontwarn java.lang.management.**
-dontwarn java.sql.JDBCType
-dontwarn java.sql.SQLType
-dontwarn java.beans.Transient

# SQLite (xerial sqlite-jdbc) for files opened from the phone. Its native library calls back into
# the Java side by class and method name (functions, collations, progress handlers), so nothing
# of it may be renamed. It also names GraalVM's and a few desktop-only classes it never reaches
# on Android.
-keep class org.sqlite.** { *; }
-dontwarn org.sqlite.**
