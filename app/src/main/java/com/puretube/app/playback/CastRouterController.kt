package com.puretube.app.playback

import android.content.Context
import androidx.mediarouter.media.MediaControlIntent
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class CastRoute(val id: String, val name: String, val isDefault: Boolean)

/**
 * MediaRouter wrapper for Chromecast / DLNA route discovery and handoff.
 * Thin by design: route selection is delegated to the system router, and the
 * system cast picker dialog remains the user-facing selection UI.
 */
class CastRouterController(context: Context) {

    private val mediaRouter: MediaRouter = MediaRouter.getInstance(context.applicationContext)
    private val selector: MediaRouteSelector = MediaRouteSelector.Builder()
        .addControlCategory(MediaControlIntent.CATEGORY_LIVE_VIDEO)
        .addControlCategory(MediaControlIntent.CATEGORY_REMOTE_PLAYBACK)
        .build()

    private val _routes = MutableStateFlow<List<CastRoute>>(emptyList())
    val routes: StateFlow<List<CastRoute>> = _routes

    private val _selectedRouteName = MutableStateFlow<String?>(null)
    val selectedRouteName: StateFlow<String?> = _selectedRouteName

    private val callback = object : MediaRouter.Callback() {
        override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
        override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
        override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
        override fun onRouteSelected(router: MediaRouter, route: MediaRouter.RouteInfo, reason: Int) {
            _selectedRouteName.value = route.name
            refresh()
        }
    }

    fun startDiscovery() {
        mediaRouter.addCallback(selector, callback, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY)
        refresh()
    }

    fun stopDiscovery() {
        mediaRouter.removeCallback(callback)
    }

    fun selectRoute(routeId: String) {
        mediaRouter.routes.firstOrNull { it.id == routeId }?.let { route ->
            mediaRouter.selectRoute(route)
            _selectedRouteName.value = route.name
        }
    }

    fun routeSelector(): MediaRouteSelector = selector

    private fun refresh() {
        _routes.value = mediaRouter.routes.map { route ->
            CastRoute(id = route.id, name = route.name, isDefault = route.isDefault)
        }
    }
}
