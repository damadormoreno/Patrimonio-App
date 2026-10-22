package com.denebapps.patrimonio.ui.screens.perfil

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.Dp
import com.denebapps.patrimonio.data.platform.AppLogger
import com.denebapps.patrimonio.domain.repository.ProfilePhotoRepository
import com.denebapps.patrimonio.ui.components.Avatar
import com.denebapps.patrimonio.ui.icons.AppIcons
import kotlinx.coroutines.CancellationException
import org.koin.compose.koinInject

/** The stored profile photo at [path]; null where photos are not kept (the web). */
expect suspend fun readProfilePhoto(path: String): ImageBitmap?

/** The profile [Avatar]: the photo when there is one, else [initials], else the user icon. */
@Composable
fun ProfileAvatar(
    initials: String?,
    size: Dp,
    modifier: Modifier = Modifier,
    photos: ProfilePhotoRepository = koinInject(),
) {
    val photo by photos.photo.collectAsState()
    val bitmap by produceState<ImageBitmap?>(null, photo) {
        value = photo?.let { current ->
            try {
                readProfilePhoto(current.path)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.error("ProfileAvatar", "Could not read the profile photo", e)
                null
            }
        }
    }
    Avatar(
        modifier = modifier,
        initials = initials,
        icon = AppIcons.user.takeIf { initials == null },
        size = size,
        photo = bitmap,
    )
}
