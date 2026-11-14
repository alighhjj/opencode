package ai.opencode.mobile.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import ai.opencode.mobile.data.Server
import ai.opencode.mobile.data.ServerStore
import ai.opencode.mobile.ui.screens.BrowserScreen
import ai.opencode.mobile.ui.screens.ConnectScreen

private data class Session(val server: Server, val path: String?)

/** Two screens only: pick a server, then hand the experience to the OpenCode web client. */
@Composable
fun OpenCodeApp() {
    val context = LocalContext.current
    val store = remember { ServerStore(context) }
    var recents by remember { mutableStateOf(store.recents()) }
    var session by remember { mutableStateOf<Session?>(null) }

    val active = session
    if (active == null) {
        ConnectScreen(
            recents = recents,
            onConnect = { server, path ->
                store.remember(server)
                recents = store.recents()
                session = Session(server, path)
            },
            onForget = { url ->
                store.forget(url)
                recents = store.recents()
            },
        )
    } else {
        BrowserScreen(
            server = active.server,
            initialPath = active.path,
            onDisconnect = { session = null },
        )
    }
}
