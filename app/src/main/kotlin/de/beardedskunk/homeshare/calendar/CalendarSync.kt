package de.beardedskunk.homeshare.calendar

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat
import de.beardedskunk.homeshare.data.EventCodec
import de.beardedskunk.homeshare.data.EventData
import de.beardedskunk.homeshare.data.FeedRepository
import de.beardedskunk.homeshare.data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import java.util.TimeZone

/**
 * Spiegelt App-Kalendereinträge **einseitig** in den internen Android-Kalender
 * (App → Kalender, nie zurück). Pro Eintrag genau ein Kalender-Event; die Zuordnung
 * liegt lokal in `calendar_link`. Bei Änderung (Edit ODER Sync-Ingest) wird das Event
 * aktualisiert, bei Löschung entfernt. Idempotent über einen Inhalts-Hash.
 */
class CalendarSync(
    context: Context,
    private val db: SQLiteDatabase,
    private val repo: FeedRepository,
    private val settings: Settings,
) {
    private val appContext = context.applicationContext
    private val cr get() = appContext.contentResolver
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mutex = Mutex()
    @Volatile private var queued = false

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** Sync anstoßen; Bursts (viele Sync-Ingest-Ops) werden zu einem Lauf zusammengefasst. */
    fun requestSync() {
        if (!hasPermission()) return
        if (queued) return
        queued = true
        scope.launch {
            delay(400)
            queued = false
            mutex.withLock { runCatching { syncAll() }.onFailure { Log.w(TAG, "Kalender-Sync fehlgeschlagen", it) } }
        }
    }

    private fun syncAll() {
        val calId = targetCalendarId() ?: return
        for (e in repo.calendarEntries()) {
            // Gelöscht ODER Feed auf diesem Gerät deaktiviert -> Event aus dem Android-Kalender entfernen.
            val enabled = settings.isCalendarFeedEnabled(e.rootId)
            val ev = if (e.deleted || !enabled) null else EventCodec.parse(e.text)
            if (ev == null) {
                deleteLinked(e.nodeId)
            } else {
                upsert(e.nodeId, ev, calId)
            }
        }
    }

    private fun upsert(postId: String, ev: EventData, calId: Long) {
        val tz = TimeZone.getDefault().id
        val times = EventTime.compute(ev, tz)
        val hash = hashOf(ev, calId)
        val link = linkFor(postId)
        if (link != null && link.hash == hash && eventExists(link.eventId)) return

        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calId)
            put(CalendarContract.Events.TITLE, ev.title)
            put(CalendarContract.Events.DESCRIPTION, ev.description)
            put(CalendarContract.Events.EVENT_LOCATION, ev.location)
            put(CalendarContract.Events.DTSTART, times.startMillis)
            put(CalendarContract.Events.EVENT_TIMEZONE, times.timeZone)
            put(CalendarContract.Events.ALL_DAY, if (ev.allDay) 1 else 0)
            put(
                CalendarContract.Events.AVAILABILITY,
                if (ev.busy) CalendarContract.Events.AVAILABILITY_BUSY else CalendarContract.Events.AVAILABILITY_FREE,
            )
            put(CalendarContract.Events.HAS_ALARM, if (ev.reminderMinutes != null) 1 else 0)
            val rrule = ev.recurrence.rrule
            if (rrule != null) {
                // Wiederkehrend: RRULE + DURATION, KEIN DTEND.
                put(CalendarContract.Events.RRULE, rrule)
                put(CalendarContract.Events.DURATION, EventTime.isoDuration(times.durationSeconds, ev.allDay))
                putNull(CalendarContract.Events.DTEND)
                putNull(CalendarContract.Events.EVENT_END_TIMEZONE)
            } else {
                put(CalendarContract.Events.DTEND, times.endMillis)
                put(CalendarContract.Events.EVENT_END_TIMEZONE, times.timeZone)
                putNull(CalendarContract.Events.RRULE)
                putNull(CalendarContract.Events.DURATION)
            }
        }

        val eventId: Long = if (link != null && eventExists(link.eventId)) {
            cr.update(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, link.eventId), values, null, null)
            link.eventId
        } else {
            val uri = cr.insert(CalendarContract.Events.CONTENT_URI, values) ?: return
            ContentUris.parseId(uri)
        }

        // Erinnerungen neu setzen (zuerst alte löschen).
        cr.delete(
            CalendarContract.Reminders.CONTENT_URI,
            "${CalendarContract.Reminders.EVENT_ID}=?",
            arrayOf(eventId.toString()),
        )
        ev.reminderMinutes?.let { min ->
            cr.insert(
                CalendarContract.Reminders.CONTENT_URI,
                ContentValues().apply {
                    put(CalendarContract.Reminders.EVENT_ID, eventId)
                    put(CalendarContract.Reminders.MINUTES, min)
                    put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                },
            )
        }
        saveLink(postId, eventId, calId, hash)
    }

    private fun deleteLinked(postId: String) {
        val link = linkFor(postId) ?: return
        runCatching {
            cr.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, link.eventId), null, null)
        }
        db.delete("calendar_link", "node_id=?", arrayOf(postId))
    }

    // ----- Zielkalender -----

    /**
     * Zielkalender = eigener **lokaler** Kalender „HomeShare" (synct NICHT zu Google,
     * vermischt sich nicht mit den persönlichen/geteilten Kalendern). Wird bei Bedarf
     * angelegt. Eine explizit gesetzte [Settings.calendarId] hat Vorrang.
     */
    private fun targetCalendarId(): Long? {
        val chosen = settings.calendarId
        if (chosen > 0 && calendarExists(chosen)) return chosen
        findLocalCalendar()?.let { return it }
        return createLocalCalendar()
    }

    private fun findLocalCalendar(): Long? =
        cr.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID),
            "${CalendarContract.Calendars.ACCOUNT_TYPE}=? AND ${CalendarContract.Calendars.ACCOUNT_NAME}=? AND ${CalendarContract.Calendars.NAME}=?",
            arrayOf(CalendarContract.ACCOUNT_TYPE_LOCAL, LOCAL_CAL_ACCOUNT, LOCAL_CAL_NAME),
            null,
        )?.use { if (it.moveToFirst()) it.getLong(0) else null }

    private fun createLocalCalendar(): Long? {
        val uri = CalendarContract.Calendars.CONTENT_URI.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, LOCAL_CAL_ACCOUNT)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            .build()
        val values = ContentValues().apply {
            put(CalendarContract.Calendars.ACCOUNT_NAME, LOCAL_CAL_ACCOUNT)
            put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(CalendarContract.Calendars.NAME, LOCAL_CAL_NAME)
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, LOCAL_CAL_NAME)
            put(CalendarContract.Calendars.CALENDAR_COLOR, 0xFF3F51B5.toInt())
            put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
            put(CalendarContract.Calendars.OWNER_ACCOUNT, LOCAL_CAL_ACCOUNT)
            put(CalendarContract.Calendars.SYNC_EVENTS, 1)
            put(CalendarContract.Calendars.VISIBLE, 1)
            put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, TimeZone.getDefault().id)
        }
        return runCatching { cr.insert(uri, values)?.let { ContentUris.parseId(it) } }
            .onFailure { Log.w(TAG, "Lokalen HomeShare-Kalender anlegen fehlgeschlagen", it) }
            .getOrNull()
    }

    private fun calendarExists(id: Long): Boolean =
        cr.query(
            ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, id),
            arrayOf(CalendarContract.Calendars._ID), null, null, null,
        )?.use { it.moveToFirst() } ?: false

    private fun eventExists(eventId: Long): Boolean =
        cr.query(
            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
            arrayOf(CalendarContract.Events._ID),
            "${CalendarContract.Events.DELETED}=0", null, null,
        )?.use { it.moveToFirst() } ?: false

    // ----- calendar_link (lokal) -----

    private data class Link(val eventId: Long, val hash: String)

    private fun linkFor(postId: String): Link? =
        db.rawQuery("SELECT event_id, synced_hash FROM calendar_link WHERE node_id=?", arrayOf(postId)).use { c ->
            if (c.moveToFirst()) Link(c.getLong(0), c.getString(1)) else null
        }

    private fun saveLink(postId: String, eventId: Long, calId: Long, hash: String) {
        db.insertWithOnConflict(
            "calendar_link", null,
            ContentValues().apply {
                put("node_id", postId)
                put("event_id", eventId)
                put("calendar_id", calId)
                put("synced_hash", hash)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    private fun hashOf(ev: EventData, calId: Long): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(("$calId\n" + EventCodec.encode(ev)).toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "CalendarSync"
        // Eigener lokaler Kalender (kein Cloud-Sync, eigene Farbe, ein-/ausblendbar).
        private const val LOCAL_CAL_NAME = "HomeShare"
        private const val LOCAL_CAL_ACCOUNT = "HomeShare"
    }
}
