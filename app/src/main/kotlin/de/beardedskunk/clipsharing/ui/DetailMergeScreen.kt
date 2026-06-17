package de.beardedskunk.clipsharing.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import de.beardedskunk.clipsharing.core.DiffOp
import de.beardedskunk.clipsharing.core.PostContent
import de.beardedskunk.clipsharing.core.PostVersion
import de.beardedskunk.clipsharing.core.TextDiff
import de.beardedskunk.clipsharing.data.BlobStore
import de.beardedskunk.clipsharing.data.Feed
import de.beardedskunk.clipsharing.data.FeedRepository
import de.beardedskunk.clipsharing.data.PostState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Detaillierte Konfliktauflösung: Statt eine ganze Fassung en bloc zu wählen,
 * führt man die *einzelnen* strittigen Teile zusammen. Angezeigt wird die zuletzt
 * auf diesem Gerät aktive Fassung wie im Editor, aber gesperrt. Strittige Stellen
 * (Text, einzelne Bilder, Bildtitel) sind rot markiert und antippbar; das Antippen
 * öffnet einen kleinen Auswahl-Dialog nur für diesen Teil. Erst wenn alle Teile
 * entschieden sind, lässt sich der Eintrag mit „Zusammenführen“ als eine einzige
 * Merge-Version festschreiben (gilt dann für alle Geräte). Solange editiert man hier
 * nichts direkt – das normale Bearbeiten ist erst nach dem Auflösen wieder möglich.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailMergeScreen(
    repo: FeedRepository,
    blobStore: BlobStore,
    feed: Feed,
    post: PostState,
    onOpenImage: (String) -> Unit,
    onResolved: () -> Unit,
    onCancel: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val maxImageHeight = (LocalConfiguration.current.screenHeightDp * 0.2f).dp

    var loaded by remember { mutableStateOf(false) }
    var heads by remember { mutableStateOf<List<PostVersion>>(emptyList()) }
    var baseText by remember { mutableStateOf("") }
    var working by remember { mutableStateOf<PostVersion?>(null) }

    var textConflict by remember { mutableStateOf(false) }
    var textVariants by remember { mutableStateOf<List<TextVariant>>(emptyList()) }
    var imageCands by remember { mutableStateOf<List<ImgCand>>(emptyList()) }

    // Auflösungs-Zustand:
    var textChosen by remember { mutableStateOf(false) }
    var chosenText by remember { mutableStateOf("") }
    var chosenDeleted by remember { mutableStateOf(false) }
    var imgDecisions by remember { mutableStateOf<Map<String, ImgDecision>>(emptyMap()) }

    // Unter-Ansichten:
    var textMergeOpen by remember { mutableStateOf(false) }
    var writingCustom by remember { mutableStateOf(false) }
    var imageMergeFor by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(post.postId) {
        withContext(Dispatchers.IO) {
            val p = repo.history(post.postId)
            val h = p.heads()
            val base = if (h.size >= 2) p.lowestCommonAncestor(h[0].versionId, h[1].versionId) else null
            val names = repo.deviceNames()
            val localId = repo.localDeviceId()
            fun label(v: PostVersion): String =
                names[v.deviceId]?.takeIf { it.isNotBlank() } ?: "Gerät ${v.deviceId.take(6)}"

            val live = h.filter { !it.content.deleted }
            val anyDeleted = h.any { it.content.deleted }
            val distinctTexts = live.map { it.content.text }.distinct()
            val tConflict = distinctTexts.size > 1 || (anyDeleted && live.isNotEmpty())

            // Text-Varianten (für den Text-Merge-Dialog).
            val variants = h.groupBy { it.content.deleted to (if (it.content.deleted) "" else it.content.text) }
                .map { (k, vs) -> TextVariant(deleted = k.first, text = k.second, devices = vs.map { label(it) }) }

            // Bild-Kandidaten: jede Bild-Id, die in mind. einer lebenden Fassung vorkommt.
            val workingHead = h.firstOrNull { it.deviceId == localId } ?: h.lastOrNull()
            fun titleFor(v: PostVersion, hash: String): String {
                val i = v.content.imageHashes.indexOf(hash)
                return if (i >= 0) v.content.imageTitles.getOrNull(i) ?: "" else ""
            }
            val allHashes = LinkedHashSet<String>().apply { live.forEach { addAll(it.content.imageHashes) } }
            val cands = allHashes.map { hash ->
                val holders = live.filter { hash in it.content.imageHashes }
                val titles = holders.map { titleFor(it, hash) }.distinct()
                ImgCand(
                    hash = hash,
                    inWorking = workingHead?.let { hash in it.content.imageHashes } ?: false,
                    agreed = holders.size == live.size && titles.size == 1,
                    titleOptions = titles,
                    holders = holders.map { label(it) },
                )
            }

            // Vorbelegung: einvernehmliche Teile gelten als entschieden.
            val initialDecisions = HashMap<String, ImgDecision>()
            cands.forEach { if (it.agreed) initialDecisions[it.hash] = ImgDecision(true, it.titleOptions.first()) }

            heads = h
            baseText = base?.content?.text ?: ""
            working = workingHead
            textConflict = tConflict
            textVariants = variants
            imageCands = cands
            imgDecisions = initialDecisions
            if (!tConflict) {
                textChosen = true
                chosenDeleted = live.isEmpty() && anyDeleted
                chosenText = distinctTexts.firstOrNull() ?: ""
            }
            loaded = true
        }
    }

    fun decisionFor(hash: String): ImgDecision? = imgDecisions[hash]
    val allResolved = textChosen && imageCands.all { imgDecisions.containsKey(it.hash) }

    fun commit() {
        val w = working
        val orderedHashes = buildList {
            w?.content?.imageHashes?.forEach { if (imageCands.any { c -> c.hash == it }) add(it) }
            imageCands.map { it.hash }.filter { w == null || it !in w.content.imageHashes }.sorted().forEach { add(it) }
        }
        val incl = orderedHashes.filter { imgDecisions[it]?.include == true }
        val titles = incl.map { imgDecisions[it]?.title ?: "" }
        val content = if (chosenDeleted) PostContent(deleted = true)
        else PostContent(text = chosenText, imageHashes = incl, imageTitles = titles, deleted = false)
        scope.launch {
            withContext(Dispatchers.IO) { repo.resolveConflict(feed.id, post.postId, content) }
            onResolved()
        }
    }

    // --- Unter-Ansicht: eigenen Text schreiben ---
    if (writingCustom) {
        PostEditor(
            initialText = chosenText.ifBlank { textVariants.firstOrNull { !it.deleted }?.text ?: "" },
            onSave = { t -> chosenText = t; chosenDeleted = false; textChosen = true; writingCustom = false; textMergeOpen = false },
            onDelete = { chosenDeleted = true; textChosen = true; writingCustom = false; textMergeOpen = false },
            onCancel = { writingCustom = false },
        )
        return
    }

    // --- Unter-Ansicht: Text zusammenführen ---
    if (textMergeOpen) {
        TextMergeView(
            variants = textVariants,
            baseText = baseText,
            onPick = { text, deleted -> chosenText = text; chosenDeleted = deleted; textChosen = true; textMergeOpen = false },
            onCustom = { writingCustom = true },
            onCancel = { textMergeOpen = false },
        )
        return
    }

    // --- Unter-Ansicht: ein Bild zusammenführen ---
    val imgFor = imageMergeFor
    if (imgFor != null) {
        val cand = imageCands.firstOrNull { it.hash == imgFor }
        if (cand != null) {
            ImageMergeView(
                cand = cand,
                blobStore = blobStore,
                current = decisionFor(imgFor),
                onOpenImage = onOpenImage,
                onDecide = { include, title -> imgDecisions = imgDecisions + (imgFor to ImgDecision(include, title)); imageMergeFor = null },
                onCancel = { imageMergeFor = null },
            )
            return
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Im Detail zusammenführen") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
                actions = {
                    IconButton(enabled = allResolved, onClick = { commit() }) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = "Zusammenführen",
                            tint = if (allResolved) Color(0xFF2E7D32) else Color.Gray,
                        )
                    }
                },
            )
        },
    ) { padding ->
        if (!loaded) return@Scaffold
        if (heads.size < 2) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Kein Konflikt mehr – bereits aufgelöst.")
            }
            return@Scaffold
        }
        Column(
            Modifier.fillMaxSize().padding(padding).padding(12.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Führe die strittigen Teile einzeln zusammen. Rot markierte Stellen antippen und je Teil eine " +
                    "Fassung wählen. Erst wenn alles gelöst ist, lässt sich der Eintrag zusammenführen.",
                style = MaterialTheme.typography.bodyMedium,
            )

            // --- Text ---
            Text("Text", fontWeight = FontWeight.Bold)
            when {
                !textConflict ->
                    Text(chosenText.ifBlank { "(kein Text)" }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                !textChosen ->
                    ConflictBlock(onClick = { textMergeOpen = true }) {
                        Text("⚠ Text unterschiedlich – antippen zum Zusammenführen", color = Color(0xFFC62828), fontWeight = FontWeight.Bold)
                        Text(
                            working?.content?.text?.ifBlank { "(kein Text)" } ?: "",
                            color = Color(0xFFC62828),
                            maxLines = 6,
                        )
                    }
                else ->
                    ResolvedBlock(onClick = { textMergeOpen = true }) {
                        if (chosenDeleted) {
                            Text("✓ Beitrag wird gelöscht – antippen zum Ändern", color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold)
                        } else {
                            Text("✓ Text gewählt – antippen zum Ändern", color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold)
                            Text(chosenText.ifBlank { "(kein Text)" })
                        }
                    }
            }

            // --- Bilder ---
            if (imageCands.isNotEmpty()) {
                Text("Bilder", fontWeight = FontWeight.Bold)
                // Reihenfolge: zuerst Bilder der Arbeitsfassung, dann zusätzliche aus anderen Fassungen.
                val w = working
                val ordered = buildList {
                    w?.content?.imageHashes?.forEach { h -> imageCands.firstOrNull { it.hash == h }?.let { add(it) } }
                    imageCands.filter { c -> w == null || c.hash !in w.content.imageHashes }.forEach { add(it) }
                }
                ordered.forEach { cand ->
                    val dec = imgDecisions[cand.hash]
                    ImageMergeRow(
                        cand = cand,
                        decision = dec,
                        blobStore = blobStore,
                        maxImageHeight = maxImageHeight,
                        onOpenImage = onOpenImage,
                        onResolve = { imageMergeFor = cand.hash },
                    )
                }
            }

            Button(enabled = allResolved, onClick = { commit() }, modifier = Modifier.fillMaxWidth()) {
                Text(if (allResolved) "Zusammenführen & übernehmen" else "Erst alle Konflikte lösen")
            }
        }
    }
}

/** Eine Bild-Zeile in der Übersicht: Thumbnail + Status (offen=rot / übernommen / entfernt). */
@Composable
private fun ImageMergeRow(
    cand: ImgCand,
    decision: ImgDecision?,
    blobStore: BlobStore,
    maxImageHeight: androidx.compose.ui.unit.Dp,
    onOpenImage: (String) -> Unit,
    onResolve: () -> Unit,
) {
    val undecided = decision == null
    when {
        cand.agreed -> Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ImageRowContent(cand, decision, blobStore, maxImageHeight, onOpenImage)
            }
        }
        undecided -> ConflictBlock(onClick = onResolve) {
            ImageRowContent(cand, decision, blobStore, maxImageHeight, onOpenImage)
        }
        else -> ResolvedBlock(onClick = onResolve) {
            ImageRowContent(cand, decision, blobStore, maxImageHeight, onOpenImage)
        }
    }
}

@Composable
private fun ImageRowContent(
    cand: ImgCand,
    decision: ImgDecision?,
    blobStore: BlobStore,
    maxImageHeight: androidx.compose.ui.unit.Dp,
    onOpenImage: (String) -> Unit,
) {
    val bmp = rememberBlobBitmap(blobStore, cand.hash, preferFull = true)
    if (bmp != null) {
        Image(
            bitmap = bmp,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth().heightIn(max = maxImageHeight).clickable { onOpenImage(cand.hash) },
        )
    } else {
        Text("🖼 (Bild nicht lokal)")
    }
    when {
        cand.agreed -> {
            Text("In allen Fassungen gleich.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (cand.titleOptions.first().isNotBlank()) Text("Titel: ${cand.titleOptions.first()}")
        }
        decision == null -> {
            Text(
                "⚠ Strittig – antippen zum Entscheiden" + if (!cand.inWorking) " (aus anderer Fassung)" else "",
                color = Color(0xFFC62828), fontWeight = FontWeight.Bold,
            )
            if (cand.titleOptions.size > 1) Text("Titel-Varianten: " + cand.titleOptions.joinToString(" / ") { it.ifBlank { "(ohne)" } }, color = Color(0xFFC62828))
        }
        decision.include -> {
            Text("✓ Wird übernommen – antippen zum Ändern", color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold)
            if (decision.title.isNotBlank()) Text("Titel: ${decision.title}")
        }
        else -> Text("✗ Wird entfernt – antippen zum Ändern", color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
    }
}

/** Rot umrandeter, antippbarer Block für einen noch offenen Teilkonflikt. */
@Composable
private fun ConflictBlock(onClick: () -> Unit, content: @Composable () -> Unit) {
    Card(
        Modifier
            .fillMaxWidth()
            .border(2.dp, Color(0xFFC62828), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
    }
}

/** Grün-getönter, antippbarer Block für einen bereits entschiedenen Teil. */
@Composable
private fun ResolvedBlock(onClick: () -> Unit, content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
    }
}

/** Auswahl-Dialog nur für den Text: jede Fassung mit Diff gegen die Basis. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TextMergeView(
    variants: List<TextVariant>,
    baseText: String,
    onPick: (text: String, deleted: Boolean) -> Unit,
    onCustom: () -> Unit,
    onCancel: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Text zusammenführen") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(12.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Gemeinsame Basis (vor den Änderungen)", fontWeight = FontWeight.Bold)
            Text(baseText.ifBlank { "(leer)" }, color = MaterialTheme.colorScheme.onSurfaceVariant)
            variants.forEach { v ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(v.devices.joinToString(", "), fontWeight = FontWeight.Bold)
                        if (v.deleted) {
                            Text("Diese Fassung löscht den Beitrag.", color = MaterialTheme.colorScheme.error)
                        } else if (baseText.isBlank()) {
                            Text(v.text.ifBlank { "(kein Text)" })
                        } else if (v.text == baseText) {
                            Text("Text unverändert.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            Text(diffText(baseText, v.text))
                        }
                        Button(onClick = { onPick(v.text, v.deleted) }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (v.deleted) "Löschen übernehmen" else "Diesen Text übernehmen")
                        }
                    }
                }
            }
            OutlinedButton(onClick = onCustom, modifier = Modifier.fillMaxWidth()) {
                Text("Eigenen Text schreiben")
            }
        }
    }
}

/** Auswahl-Dialog nur für ein Bild: übernehmen (mit Titel-Variante) oder entfernen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImageMergeView(
    cand: ImgCand,
    blobStore: BlobStore,
    current: ImgDecision?,
    onOpenImage: (String) -> Unit,
    onDecide: (include: Boolean, title: String) -> Unit,
    onCancel: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Bild zusammenführen") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(12.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val bmp = rememberBlobBitmap(blobStore, cand.hash, preferFull = true)
            if (bmp != null) {
                Image(
                    bitmap = bmp,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp).clickable { onOpenImage(cand.hash) },
                )
            } else {
                Text("🖼 (Bild nicht lokal)")
            }
            Text("In diesen Fassungen enthalten: ${cand.holders.joinToString(", ")}", style = MaterialTheme.typography.labelMedium)
            Text("Soll dieses Bild im zusammengeführten Eintrag bleiben?", fontWeight = FontWeight.Bold)
            // Pro Titel-Variante ein „Übernehmen“-Knopf.
            cand.titleOptions.forEach { t ->
                Button(onClick = { onDecide(true, t) }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (t.isBlank()) "Übernehmen (ohne Titel)" else "Übernehmen mit Titel „$t“")
                }
            }
            OutlinedButton(onClick = { onDecide(false, "") }, modifier = Modifier.fillMaxWidth()) {
                Text("Nicht übernehmen (entfernen)")
            }
        }
    }
}

private data class TextVariant(val deleted: Boolean, val text: String, val devices: List<String>)
private data class ImgCand(
    val hash: String,
    val inWorking: Boolean,
    val agreed: Boolean,
    val titleOptions: List<String>,
    val holders: List<String>,
)
private data class ImgDecision(val include: Boolean, val title: String)

/** Wort-Diff (Basis -> Ziel) eingefärbt: grün = neu, rot durchgestrichen = entfernt. */
private fun diffText(base: String, target: String): AnnotatedString = buildAnnotatedString {
    for (seg in TextDiff.diff(base, target)) {
        when (seg.op) {
            DiffOp.EQUAL -> append(seg.text)
            DiffOp.INSERT -> withStyle(SpanStyle(color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold)) { append(seg.text) }
            DiffOp.DELETE -> withStyle(SpanStyle(color = Color(0xFFC62828), textDecoration = TextDecoration.LineThrough)) { append(seg.text) }
        }
    }
}
