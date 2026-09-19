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
