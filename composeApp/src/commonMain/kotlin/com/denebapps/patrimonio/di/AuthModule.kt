package com.denebapps.patrimonio.di

import com.denebapps.patrimonio.data.auth.AuthRepositoryImpl
import com.denebapps.patrimonio.data.auth.AuthSessionStore
import com.denebapps.patrimonio.data.auth.DataStoreAuthSessionStore
import com.denebapps.patrimonio.data.auth.FirebaseAuthApi
import com.denebapps.patrimonio.data.cloud.CloudBackupRemote
import com.denebapps.patrimonio.data.cloud.CloudBackupSync
import com.denebapps.patrimonio.data.cloud.CloudLinkStore
import com.denebapps.patrimonio.data.cloud.DataStoreCloudLinkStore
import com.denebapps.patrimonio.data.cloud.FirestoreBackupApi
import com.denebapps.patrimonio.data.cloud.LocalDataChanges
import com.denebapps.patrimonio.data.datastore.AUTH_SESSION_FILE_NAME
import com.denebapps.patrimonio.data.datastore.CLOUD_LINK_FILE_NAME
import com.denebapps.patrimonio.data.datastore.dataStoreFilePath
import com.denebapps.patrimonio.data.db.RoomLocalDataChanges
import com.denebapps.patrimonio.data.platform.PlatformContext
import com.denebapps.patrimonio.domain.repository.AuthRepository
import com.denebapps.patrimonio.domain.repository.CloudBackup
import org.koin.dsl.module

/** Optional cloud account and its automatic backup. Reuses the [fxModule] HttpClient. */
val authModule = module {
    single { FirebaseAuthApi(get()) }
    single<AuthSessionStore> {
        DataStoreAuthSessionStore(dataStoreFilePath(get<PlatformContext>(), AUTH_SESSION_FILE_NAME))
    }
    single<AuthRepository> { AuthRepositoryImpl(get(), get(), get()) }
    single<CloudBackupRemote> { FirestoreBackupApi(get()) }
    single<CloudLinkStore> { DataStoreCloudLinkStore(dataStoreFilePath(get<PlatformContext>(), CLOUD_LINK_FILE_NAME)) }
    single<LocalDataChanges> { RoomLocalDataChanges(get()) }
    single<CloudBackup> {
        CloudBackupSync(
            authRepository = get(),
            backupRepository = get(),
            remote = get(),
            linkStore = get(),
            localChanges = get(),
            clock = get(),
        )
    }
}
