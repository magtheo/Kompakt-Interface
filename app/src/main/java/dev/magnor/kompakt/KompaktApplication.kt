package dev.magnor.kompakt

import android.app.Application
import dev.magnor.kompakt.data.AppContainer

/** Process-lifetime owner of the dependency container (manual DI). */
class KompaktApplication : Application() {
    val container: AppContainer by lazy { AppContainer() }
}
