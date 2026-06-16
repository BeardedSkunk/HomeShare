package de.beardedskunk.clipsharing

import android.app.Application
import android.content.Context
import de.beardedskunk.clipsharing.data.Db
import de.beardedskunk.clipsharing.data.DeviceIdentity
import de.beardedskunk.clipsharing.data.FeedRepository

/**
 * Sehr schlanker Service-Locator. Haelt die langlebigen Objekte (DB, Identitaet,
 * Repository). DB wird lazy geoeffnet -> der erste Zugriff sollte aus einem
 * Hintergrund-Thread (Dispatchers.IO) erfolgen, nicht vom Main-Thread.
 */
class AppGraph(context: Context) {
    private val appContext = context.applicationContext
    val identity: DeviceIdentity by lazy { DeviceIdentity(appContext) }
    private val db by lazy { Db(appContext).writableDatabase }
    val repo: FeedRepository by lazy { FeedRepository(db, identity) }
}

class ClipApplication : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}

/** Bequemer Zugriff auf den Graph aus Activities/Composables. */
val Context.appGraph: AppGraph
    get() = (applicationContext as ClipApplication).graph
