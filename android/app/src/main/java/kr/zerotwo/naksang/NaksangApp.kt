package kr.zerotwo.naksang

import android.app.Application
import kr.zerotwo.naksang.session.AppContainer

class NaksangApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
