package com.denebapps.patrimonio

import android.app.Application
import com.denebapps.patrimonio.di.initKoin
import org.koin.android.ext.koin.androidContext

class PatrimonioApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        initKoin {
            androidContext(this@PatrimonioApplication)
        }
    }
}
