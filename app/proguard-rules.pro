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

# SQLCipher native bindings.
-keep class net.zetetic.database.** { *; }

# sshj compiles against JDK/servlet APIs that are absent on Android.
-dontwarn java.awt.**
-dontwarn javax.annotation.**
-dontwarn org.slf4j.**
