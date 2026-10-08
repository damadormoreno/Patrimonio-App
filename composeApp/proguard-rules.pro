# R8 rules for the release and preview builds.
#
# The libraries ship their own rules (kotlinx.serialization, Room, WorkManager, DataStore, Ktor, Koin), and
# cryptography-kotlin's providers are found through ServiceLoader, which R8 keeps on Android. Add a rule
# here only for a failure seen in a minified build, with a comment saying which one.

# Readable stack traces from the mapping file (uploaded by CI next to every minified build).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
