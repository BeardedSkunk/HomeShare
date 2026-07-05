package de.beardedskunk.homeshare.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import de.beardedskunk.homeshare.core.NodeContent
import de.beardedskunk.homeshare.core.NodeType
import de.beardedskunk.homeshare.data.BlobStore
import de.beardedskunk.homeshare.data.EventCodec
import de.beardedskunk.homeshare.data.EventData
import de.beardedskunk.homeshare.data.FeedRepository
import de.beardedskunk.homeshare.data.NodeState
import de.beardedskunk.homeshare.data.Recurrence
import de.beardedskunk.homeshare.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private val DATE_UI = DateTimeFormatter.ofPattern("dd.MM.yyyy")
private val HM = DateTimeFormatter.ofPattern("HH:mm")

private val reminderOptions: List<Pair<String, Int?>> = listOf(
    "Keine" to null,
    "Zur Startzeit" to 0,
    "10 Minuten vorher" to 10,
    "30 Minuten vorher" to 30,
    "1 Stunde vorher" to 60,
    "1 Tag vorher" to 1440,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarEntryEditor(
    repo: FeedRepository,
    blobStore: BlobStore,
    parentId: String,
    post: NodeState?,
    settings: Settings,
    onShare: (() -> Unit)? = null,
    onRequestCalendarSync: () -> Unit = {},
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val revision by repo.revision.collectAsState()

    // Lazy-Create: ein neuer Termin bekommt seine nodeId erst beim ersten persist(). Danach sind
    // Anhänge und der Menü-Sync-Toggle möglich.
    var currentNodeId by remember { mutableStateOf(post?.nodeId) }
    val persistMutex = remember { Mutex() }

    // Anhänge (Bilder/Dateien) als separate Kindknoten des CALENDAR-Knotens – erst ab vorhandener
    // nodeId. Werden bewusst NICHT in den Android-Kalender gesynct, syncen aber in unserer App.
    var attachments by remember { mutableStateOf<List<AttachmentRow>>(emptyList()) }
    var attOpen by remember { mutableStateOf<NodeState?>(null) }
    var openTrash by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(currentNodeId, revision) {
        val id = currentNodeId
        attachments = if (id != null) withContext(Dispatchers.IO) { loadAttachmentRows(repo, id) } else emptyList()
    }

    val existing = remember(post?.headVersionId) { post?.text?.let { EventCodec.parse(it) } }
    val now = remember { LocalDateTime.now() }
    val defStart = remember { now.toLocalTime().plusHours(1).withMinute(0).withSecond(0).withNano(0) }

    // Titel (1. Zeile) + Markdown-Beschreibung (Rest) in EINEM Feld – wie Notiz/Liste.
    val initialHeader = remember(post?.headVersionId) {
        if (existing == null) ""
        else existing.title + (if (existing.description.isNotBlank()) "\n" + existing.description else "")
    }
    var headerTfv by remember { mutableStateOf(TextFieldValue(initialHeader)) }
    val headerText = headerTfv.text
    var sourceMode by remember { mutableStateOf(post == null) }
    var headerExpanded by remember { mutableStateOf(true) } // default ausgeklappt (anders als ListScreen)

    var allDay by remember { mutableStateOf(existing?.allDay ?: false) }
    var startDate by remember { mutableStateOf(existing?.let { dateOf(it.start) } ?: now.toLocalDate()) }
    var startTime by remember { mutableStateOf(existing?.takeIf { !it.allDay }?.let { timeOf(it.start) } ?: defStart) }
    var endDate by remember { mutableStateOf(existing?.let { dateOf(it.end) } ?: now.toLocalDate()) }
    var endTime by remember { mutableStateOf(existing?.takeIf { !it.allDay }?.let { timeOf(it.end) } ?: defStart.plusHours(1)) }
    var location by remember { mutableStateOf(existing?.location ?: "") }
    var reminder by remember { mutableStateOf(existing?.reminderMinutes) }
    var recurrence by remember { mutableStateOf(existing?.recurrence ?: Recurrence.NONE) }
    var busy by remember { mutableStateOf(existing?.busy ?: true) }

    // ---- Suche (im Kopf-Text) ----
    var findQuery by remember { mutableStateOf<String?>(null) }
    var matchIdx by remember { mutableStateOf(0) }
    val findFocus = remember { FocusRequester() }
    val matches: List<Int> = remember(headerText, findQuery) {
        val q = findQuery
        if (q.isNullOrBlank()) emptyList() else findAllMatches(headerText, q)
    }
    LaunchedEffect(findQuery, sourceMode) { matchIdx = 0 }
    fun jumpTo(index: Int) {
        if (matches.isEmpty()) return
        val i = ((index % matches.size) + matches.size) % matches.size
        matchIdx = i
        headerTfv = headerTfv.copy(selection = TextRange(matches[i], matches[i] + (findQuery ?: "").length))
        findFocus.requestFocus()
    }
    fun stepMatch(delta: Int) {
        if (matches.isEmpty()) return
        val next = ((matchIdx + delta) % matches.size + matches.size) % matches.size
        if (sourceMode) jumpTo(next) else matchIdx = next // render: nur Zähler, kein Scroll-to-Match
    }

    // Baut EventData aus den aktuellen States (Titel/Beschreibung aus dem Kopf-Text gesplittet).
    fun buildEvent(): EventData {
        val zone = ZoneId.systemDefault()
        val start: String
        val end: String
        if (allDay) {
            start = startDate.toString()
            end = (if (endDate.isBefore(startDate)) startDate else endDate).toString()
        } else {
            start = ZonedDateTime.of(startDate, startTime, zone).toString()
            val endZdt = ZonedDateTime.of(endDate, endTime, zone)
            val startZdt = ZonedDateTime.of(startDate, startTime, zone)
            end = (if (endZdt.isBefore(startZdt)) startZdt else endZdt).toString()
        }
        return EventData(
            title = postTitle(headerText).trim(),
            start = start,
            end = end,
            allDay = allDay,
            location = location.trim(),
            description = postBody(headerText).trim(),
            reminderMinutes = reminder,
            recurrence = recurrence,
            busy = busy,
        )
    }

    // Legt den Knoten bei Bedarf an (Neuanlage) bzw. aktualisiert ihn; serialisiert über [persistMutex],
    // damit zwei schnelle Feldänderungen nicht zwei Knoten erzeugen. Gibt die (ggf. neue) nodeId zurück.
    suspend fun ensureNode(): String = persistMutex.withLock {
        val text = EventCodec.encode(buildEvent())
        val nid = withContext(Dispatchers.IO) {
            val id = currentNodeId
            if (id == null) {
                repo.createNode(NodeContent(parentId = parentId, type = NodeType.CALENDAR, text = text)).nodeId
            } else {
                val hc = repo.headContent(id) ?: NodeContent(parentId = parentId, type = NodeType.CALENDAR)
                repo.editNode(id, hc.copy(text = text, type = NodeType.CALENDAR))
                id
            }
        }
        currentNodeId = nid
        nid
    }

    // Zuletzt persistierter Ort – das Ort-Feld schreibt nur bei Fokusverlust (nicht pro Tastendruck).
    var persistedLocation by remember { mutableStateOf(existing?.location ?: "") }
    fun persist() {
        persistedLocation = location
        scope.launch { ensureNode() }
    }
    fun persistIfDirty() {
        if (location != persistedLocation) persist()
    }

    fun delete() {
        val id = currentNodeId ?: post?.nodeId
        if (id == null) { onClose(); return }
        scope.launch {
            withContext(Dispatchers.IO) { repo.deleteNode(id) }
            onClose()
        }
    }

    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            val entryId = ensureNode()
            withContext(Dispatchers.IO) { uris.forEach { AttachmentPicker.addImage(context, repo, blobStore, entryId, it) } }
        }
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val entryId = ensureNode()
            withContext(Dispatchers.IO) { AttachmentPicker.addFile(context, repo, blobStore, entryId, uri) }
        }
    }

    attOpen?.let { a ->
        BackHandler { attOpen = null }
        AttachmentDetailScreen(repo = repo, blobStore = blobStore, attachment = a, onClose = { attOpen = null })
        return
    }

    // Hamburger-Menü: Sync-Toggle spiegelt den effektiven Zustand (Override oder Feed-Default).
    var overflowOpen by remember { mutableStateOf(false) }
    var entrySyncOn by remember(post?.nodeId) {
        mutableStateOf(post?.let { settings.calendarEntrySyncOverride(it.nodeId) ?: settings.isCalendarFeedEnabled(it.rootId) } ?: true)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = { BackIconButton(onClick = onClose) },
                actions = {
                    // Lupe: In-Text-Suche im Kopf-Text ein/aus.
                    IconButton(
                        onClick = { findQuery = if (findQuery == null) "" else null },
                        modifier = Modifier.tag("topbar:search"),
                    ) { Icon(Icons.Filled.Search, contentDescription = "Suchen") }
                    // QR: übergeordnete Liste teilen (nur wenn ein Teilen-Callback vorliegt).
                    if (onShare != null) {
                        IconButton(onClick = onShare, modifier = Modifier.tag("topbar:share")) {
                            Icon(Icons.Filled.QrCode2, contentDescription = "Diese Liste teilen")
                        }
                    }
                    // Hamburger: Löschen + Einzel-Termin-Sync (erst ab vorhandenem Knoten).
                    if (post != null || currentNodeId != null) {
                        Box {
                            IconButton(onClick = { overflowOpen = true }, modifier = Modifier.tag("topbar:overflow")) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "Weitere Aktionen")
                            }
                            DropdownMenu(expanded = overflowOpen, onDismissRequest = { overflowOpen = false }) {
                                DropdownMenuItem(
                                    leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                                    text = { Text("Termin löschen") },
                                    onClick = { overflowOpen = false; delete() },
                                    modifier = Modifier.tag("menu:delete-entry"),
                                )
                                // Nur bei bereits gespeichertem Termin – Neuanlagen erben ohnehin den Feed-Default.
                                if (post != null) {
                                    DropdownMenuItem(
                                        text = { Text("In Android-Kalender übernehmen") },
                                        trailingIcon = { Switch(checked = entrySyncOn, onCheckedChange = null) },
                                        onClick = {
                                            val n = !entrySyncOn
                                            entrySyncOn = n
                                            settings.setCalendarEntrySync(post.nodeId, n)
                                            onRequestCalendarSync()
                                            overflowOpen = false
                                        },
                                        modifier = Modifier.tag("menu:calendar-entry-sync"),
                                    )
                                }
                            }
                        }
                    }
                    // ✓/✎-Toggle: im Quelltext-Modus persistieren + auf Render umschalten.
                    EditToggleButton(sourceMode = sourceMode, onToggle = {
                        if (sourceMode) { persist(); sourceMode = false } else sourceMode = true
                    })
                },
            )
        },
        floatingActionButton = {
            AttachmentAddFab(
                onPickImages = { pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onPickFile = { pickFile.launch(arrayOf("*/*")) },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            // Such-Leiste FIX oben (ausserhalb des Scrolls).
            if (findQuery != null) {
                FindBar(
                    query = findQuery ?: "",
                    onQuery = { findQuery = it; matchIdx = 0 },
                    label = if (matches.isEmpty()) "0/0" else "${matchIdx + 1}/${matches.size}",
                    hasMatches = matches.isNotEmpty(),
                    onPrev = { stepMatch(-1) },
                    onNext = { stepMatch(1) },
                )
            }
            Column(
                Modifier.fillMaxSize().imePadding().padding(16.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Kopf: Titel + Markdown-Beschreibung (gerendert bzw. als gemeinsames Quelltext-Feld).
                if (sourceMode) {
                    OutlinedTextField(
                        value = headerTfv,
                        onValueChange = { headerTfv = it },
                        placeholder = { Text("Titel (1. Zeile), dann Markdown…") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth().tag("field:calbody").focusRequester(findFocus),
                    )
                } else {
                    val hTitle = postTitle(headerText)
                    val hasBody = postBody(headerText).isNotBlank()
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            hTitle.ifBlank { "(ohne Titel)" },
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f).tag("header:title"),
                        )
                        if (hasBody) ExpandChevron(expanded = headerExpanded, onToggle = { headerExpanded = !headerExpanded })
                    }
                    if (headerExpanded && hasBody) {
                        MarkdownBody(text = headerText, modifier = Modifier.fillMaxWidth().padding(top = 4.dp), highlight = findQuery)
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Ganztägig", modifier = Modifier.weight(1f))
                    Switch(checked = allDay, onCheckedChange = { allDay = it; persist() })
                }

                Text("Start", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Beim Verschieben des Starts die Dauer beibehalten (Ende wandert mit).
                    DateField("Datum", startDate, Modifier.weight(1f)) { picked ->
                        if (allDay) {
                            val days = ChronoUnit.DAYS.between(startDate, endDate).coerceAtLeast(0)
                            startDate = picked
                            endDate = picked.plusDays(days)
                        } else {
                            val dur = Duration.between(LocalDateTime.of(startDate, startTime), LocalDateTime.of(endDate, endTime))
                            startDate = picked
                            val ne = LocalDateTime.of(picked, startTime).plus(if (dur.isNegative) Duration.ZERO else dur)
                            endDate = ne.toLocalDate(); endTime = ne.toLocalTime()
                        }
                        persist()
                    }
                    if (!allDay) TimeField("Zeit", startTime, Modifier.weight(1f)) { picked ->
                        val dur = Duration.between(LocalDateTime.of(startDate, startTime), LocalDateTime.of(endDate, endTime))
                        startTime = picked
                        val ne = LocalDateTime.of(startDate, picked).plus(if (dur.isNegative) Duration.ZERO else dur)
                        endDate = ne.toLocalDate(); endTime = ne.toLocalTime()
                        persist()
                    }
                }

                Text("Ende", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DateField("Datum", endDate, Modifier.weight(1f)) { endDate = it; persist() }
                    if (!allDay) TimeField("Zeit", endTime, Modifier.weight(1f)) { endTime = it; persist() }
                }

                OutlinedTextField(
                    location, { location = it },
                    label = { Text("Ort") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().onFocusChanged { if (!it.isFocused) persistIfDirty() },
                )

                LabeledDropdown(
                    label = "Erinnerung",
                    current = reminderOptions.firstOrNull { it.second == reminder }?.first ?: "Keine",
                    options = reminderOptions.map { it.first },
                    onSelect = { sel -> reminder = reminderOptions.first { it.first == sel }.second; persist() },
                )
                LabeledDropdown(
                    label = "Wiederholung",
                    current = recurrence.label,
                    options = Recurrence.entries.map { it.label },
                    onSelect = { sel -> recurrence = Recurrence.entries.first { it.label == sel }; persist() },
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (busy) "Als gebucht anzeigen" else "Als frei anzeigen", modifier = Modifier.weight(1f))
                    Switch(checked = busy, onCheckedChange = { busy = it; persist() })
                }

                Text("Anhänge", style = MaterialTheme.typography.titleSmall)
                AttachmentBox(
                    attachments, blobStore,
                    openTrashKey = openTrash,
                    onOpenTrash = { openTrash = it },
                    onDelete = { a -> openTrash = null; scope.launch { withContext(Dispatchers.IO) { repo.deleteNode(a.node.nodeId) } } },
                    onReorder = { moved, prev, next -> scope.launch { withContext(Dispatchers.IO) { repo.reorderNode(moved.nodeId, prev, next) } } },
                    onOpen = { attOpen = it.node },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateField(label: String, date: LocalDate, modifier: Modifier = Modifier, onPick: (LocalDate) -> Unit) {
    var show by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { show = true }, modifier = modifier) {
        Text("$label: ${date.format(DATE_UI)}")
    }
    if (show) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { show = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    show = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { show = false }) { Text("Abbrechen") } },
        ) { DatePicker(state = state) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeField(label: String, time: LocalTime, modifier: Modifier = Modifier, onPick: (LocalTime) -> Unit) {
    var show by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { show = true }, modifier = modifier) {
        Text("$label: ${time.format(HM)}")
    }
    if (show) {
        val state = rememberTimePickerState(initialHour = time.hour, initialMinute = time.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { show = false },
            confirmButton = { TextButton(onClick = { onPick(LocalTime.of(state.hour, state.minute)); show = false }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { show = false }) { Text("Abbrechen") } },
            text = { TimePicker(state = state) },
        )
    }
}

/** Aufklappbares Auswahlfeld. */
@Composable
private fun LabeledDropdown(label: String, current: String, options: List<String>, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
                Text(current, modifier = Modifier.weight(1f))
                Text("▾")
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                for (opt in options) {
                    DropdownMenuItem(text = { Text(opt) }, onClick = { open = false; onSelect(opt) })
                }
            }
        }
    }
}

/** Kompakte Listenzeile für einen Kalendereintrag: Titel + Datum/Zeit + Ort. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun CalendarRow(post: NodeState, onClick: () -> Unit, onLongClick: () -> Unit = {}, trailing: (@Composable () -> Unit)? = null) {
    val ev = remember(post.headVersionId) { EventCodec.parse(post.text) }
    androidx.compose.material3.Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            .tag(rowTag(ev?.title ?: post.text)),
        colors = if (post.conflicted) {
            androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        } else {
            androidx.compose.material3.CardDefaults.cardColors()
        },
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        // combinedClickable nur auf dem Inhalt (weight 1f) -> Long-Press auf dem Ziehgriff öffnet nicht.
        Column(Modifier.weight(1f).combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(14.dp)) {
            Text(
                (if (post.conflicted) "⚠ " else "") +
                    (ev?.title?.ifBlank { "(ohne Titel)" } ?: post.text.lineSequence().firstOrNull().orEmpty()),
                style = MaterialTheme.typography.titleMedium,
            )
            if (ev != null) {
                Text(formatWhen(ev), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                if (ev.location.isNotBlank()) Text("📍 ${ev.location}", style = MaterialTheme.typography.bodySmall)
                if (ev.recurrence != Recurrence.NONE) Text("🔁 ${ev.recurrence.label}", style = MaterialTheme.typography.bodySmall)
            }
        }
            trailing?.invoke()
        }
    }
}

private fun formatWhen(ev: EventData): String = try {
    if (ev.allDay) {
        val d = LocalDate.parse(ev.start.substringBefore('T'))
        val e = LocalDate.parse(ev.end.substringBefore('T'))
        if (e.isAfter(d)) "${d.format(DATE_UI)} – ${e.format(DATE_UI)} · ganztägig"
        else "${d.format(DATE_UI)} · ganztägig"
    } else {
        val s = parseZoned(ev.start)
        val e = parseZoned(ev.end)
        val sameDay = s.toLocalDate() == e.toLocalDate()
        if (sameDay) "${s.format(DATE_UI)} ${s.format(HM)} – ${e.format(HM)}"
        else "${s.format(DATE_UI)} ${s.format(HM)} – ${e.format(DATE_UI)} ${e.format(HM)}"
    }
} catch (_: Exception) {
    ev.start
}

private fun parseZoned(s: String): ZonedDateTime {
    val t = s.trim()
    runCatching { return ZonedDateTime.parse(t) }
    runCatching { return OffsetDateTime.parse(t).toZonedDateTime() }
    return LocalDateTime.parse(t).atZone(ZoneId.systemDefault())
}

private fun dateOf(s: String): LocalDate = runCatching { parseZoned(s).toLocalDate() }
    .getOrElse { runCatching { LocalDate.parse(s.substringBefore('T')) }.getOrDefault(LocalDate.now()) }

private fun timeOf(s: String): LocalTime = runCatching { parseZoned(s).toLocalTime() }
    .getOrDefault(LocalTime.of(9, 0))
