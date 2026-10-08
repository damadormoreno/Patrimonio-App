package com.denebapps.patrimonio.data.datastore

import com.denebapps.patrimonio.data.platform.PlatformContext

/** Android: `context.filesDir`. iOS: Application Support `datastore/` (okio Path resolved by the DataStore
 *  factory from this plain [String]). Each [fileName] is its own DataStore (one instance per file). */
expect fun dataStoreFilePath(context: PlatformContext, fileName: String = PREFERENCES_FILE_NAME): String

const val PREFERENCES_FILE_NAME = "patrimonio.preferences_pb"

/** Kept apart from the preferences so Android's auto backup can leave the session tokens out. */
const val AUTH_SESSION_FILE_NAME = "patrimonio-auth.preferences_pb"

/** Which account this device last backed up to; excluded from Android's auto backup like the session. */
const val CLOUD_LINK_FILE_NAME = "patrimonio-cloud.preferences_pb"

/** The app lock (PIN hash, biometrics, wrong attempts). Backed up with the data it protects. */
const val APP_LOCK_FILE_NAME = "patrimonio-lock.preferences_pb"

/** The profile photo, next to the DataStore files (a plain JPEG, not a DataStore). */
const val PROFILE_PHOTO_FILE_NAME = "profile-photo.jpg"
