package com.denebapps.patrimonio.di

import com.denebapps.patrimonio.data.auth.AuthRepositoryImpl
import com.denebapps.patrimonio.data.auth.AuthSessionStore
import com.denebapps.patrimonio.data.auth.DataStoreAuthSessionStore
import com.denebapps.patrimonio.data.auth.FirebaseAuthApi
import com.denebapps.patrimonio.data.datastore.AUTH_SESSION_FILE_NAME
import com.denebapps.patrimonio.data.datastore.dataStoreFilePath
import com.denebapps.patrimonio.data.platform.PlatformContext
import com.denebapps.patrimonio.domain.repository.AuthRepository
import org.koin.dsl.module

/** Optional cloud account. Reuses the [fxModule] HttpClient. */
val authModule = module {
    single { FirebaseAuthApi(get()) }
    single<AuthSessionStore> {
        DataStoreAuthSessionStore(dataStoreFilePath(get<PlatformContext>(), AUTH_SESSION_FILE_NAME))
    }
    single<AuthRepository> { AuthRepositoryImpl(get(), get(), get()) }
}
