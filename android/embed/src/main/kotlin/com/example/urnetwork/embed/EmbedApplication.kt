// The app's process: it holds the one EmbedController, so the embedded device outlives the activity
// (for example a rotation) and closes only on Stop, a sign-out or the end of the process.
package com.example.urnetwork.embed

import android.app.Application

/** The application, holding the app-scoped device controller. */
class EmbedApplication : Application() {
    /** The process's one controller, created on first use. */
    val controller: EmbedController by lazy { EmbedController(this) }
}
