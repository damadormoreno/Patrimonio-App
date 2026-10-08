package com.denebapps.patrimonio.domain.repository

import kotlinx.coroutines.flow.StateFlow

/** The profile photo file on this device; [version] changes with every change, so images reload. */
data class ProfilePhoto(val path: String, val version: Int)

/**
 * The profile photo, kept only on this device (it is not part of the cloud backup). It comes from Google on
 * sign-in, or from the gallery, always stored small (a square-ish JPEG of at most 512 px).
 */
interface ProfilePhotoRepository {
    /** Null without a photo. */
    val photo: StateFlow<ProfilePhoto?>

    /** Replaces the photo with [image] (any size or format the platform decodes). */
    suspend fun setImage(image: ByteArray)

    /** Downloads the Google profile photo, unless there is already a photo: the user's choice wins. */
    suspend fun setFromGoogleIfEmpty(photoUrl: String)

    suspend fun clear()
}
