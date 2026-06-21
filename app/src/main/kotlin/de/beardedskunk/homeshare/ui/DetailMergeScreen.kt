package de.beardedskunk.homeshare.ui

import androidx.compose.runtime.Composable
import de.beardedskunk.homeshare.data.BlobStore
import de.beardedskunk.homeshare.data.FeedRepository
import de.beardedskunk.homeshare.data.NodeState

/**
 * „Im Detail zusammenführen": Im Knoten-Baum ist ein Text-Eintrag nur Text (Bilder/Dateien sind
 * eigene Kindknoten mit eigener Historie), daher gibt es hier nichts „Teil für Teil" mehr zu mergen –
 * es bleibt die Text-Fassungswahl. Delegiert deshalb an [ConflictScreen]. (Später, wenn Teilbäume
 * als Ganzes Konflikte haben können, kann hier wieder eine reichere Detail-Ansicht entstehen.)
 */
@Composable
fun DetailMergeScreen(
    repo: FeedRepository,
    blobStore: BlobStore,
    feed: NodeState,
    post: NodeState,
    onOpenImage: (String) -> Unit,
    onResolved: () -> Unit,
    onCancel: () -> Unit,
) {
    ConflictScreen(
        repo = repo, blobStore = blobStore, feed = feed, post = post,
        onOpenImage = onOpenImage, onResolved = onResolved, onCancel = onCancel,
    )
}
