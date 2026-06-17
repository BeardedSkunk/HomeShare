package de.beardedskunk.clipsharing

import android.app.Application
import android.content.Context
import de.beardedskunk.clipsharing.data.BlobStore
import de.beardedskunk.clipsharing.data.Db
import de.beardedskunk.clipsharing.data.DeviceIdentity
import de.beardedskunk.clipsharing.data.FeedRepository
import de.beardedskunk.clipsharing.data.Settings
import de.beardedskunk.clipsharing.data.androidThumbnailer
import de.beardedskunk.clipsharing.backup.FritzController
import de.beardedskunk.clipsharing.sync.AutoSync
import de.beardedskunk.clipsharing.sync.SyncManager
import de.beardedskunk.clipsharing.web.WebServerController

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
    val blobStore: BlobStore by lazy { BlobStore(appContext.filesDir, ::androidThumbnailer) }
    val sync: SyncManager by lazy { SyncManager(appContext, repo, identity, settings) }
    val web: WebServerController by lazy { WebServerController(repo, blobStore) }
    val settings: Settings by lazy { Settings(appContext) }
    val fritz: FritzController by lazy { FritzController(settings, identity, repo, blobStore) }
    val autoSync: AutoSync by lazy { AutoSync(appContext, settings, fritz, sync) }
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
