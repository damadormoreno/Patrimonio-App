# R8 rules for the release and preview builds.
#
# The libraries ship their own rules (kotlinx.serialization, Room, WorkManager, DataStore, Ktor, Koin), and
# cryptography-kotlin's providers are found through ServiceLoader, which R8 keeps on Android. Add a rule
# here only for a failure seen in a minified build, with a comment saying which one.

# Readable stack traces from the mapping file (uploaded by CI next to every minified build).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# R8 renames classes, so code must not compare KClass names with strings fixed at compile time: the tab
# lookup used `Patrimonio::class.qualifiedName` against navigation's route names and lost the header
# and the FAB. Use the serial name instead (TabMapping.routeName).
