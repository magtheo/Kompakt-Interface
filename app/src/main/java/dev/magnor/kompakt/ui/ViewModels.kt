package dev.magnor.kompakt.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.magnor.kompakt.data.AppContainer

/**
 * Manual DI: one AppContainer per process, provided at the root and
 * consumed by screens through [containerViewModel]. No DI framework —
 * the APK stays auditable (docs/technical-architecture.md).
 */
val LocalAppContainer = staticCompositionLocalOf<AppContainer> {
    error("AppContainer not provided")
}

/**
 * Create (or reuse) a ViewModel built from the app container. Pass a `key`
 * for ViewModels parameterized by navigation arguments (ids).
 */
@Composable
inline fun <reified VM : ViewModel> containerViewModel(
    key: String? = null,
    noinline create: (AppContainer) -> VM,
): VM {
    val container = LocalAppContainer.current
    return viewModel(
        key = key,
        factory = viewModelFactory {
            initializer { create(container) }
        },
    )
}
