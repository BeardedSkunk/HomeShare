package de.beardedskunk.clipsharing.data

import de.beardedskunk.clipsharing.core.Hlc

/**
 * Ein benannter Feed. [calendar] = nur Kalendereinträge.
 * [shared] = eigener Feed, der an Fremdgruppen freigegeben ist (#10).
 * [foreignOrigin] != "" = dies ist ein FREMDFEED (von Gruppe [foreignOrigin] geteilt);
 * dann gilt [foreignRight] als hiesige Rechtestufe.
 */
data class Feed(
    val id: String,
    val name: String,
    val created: Hlc,
    val deleted: Boolean = false,
    val calendar: Boolean = false,
    val shared: Boolean = false,
    val foreignOrigin: String = "",
    val foreignRight: FeedRight = FeedRight.READ,
) {
    val isForeign: Boolean get() = foreignOrigin.isNotEmpty()
}

/** Lokaler Datensatz eines abonnierten Fremdfeeds (auf dem Fremdgerät). */
data class ForeignFeedRef(
    val feedId: String,
    val originGroup: String,
    val capId: String,
    val capSecret: String,
    val right: FeedRight,
)

/**
 * Der materialisierte aktuelle Stand eines Posts (aus dem Op-Log abgeleitet).
 * Bei [conflicted] = true zeigt die UI einen Hinweis und bietet die Aufloesung an;
 * [text]/[imageHashes]/[deleted] beziehen sich dann auf den angezeigten Head.
 */
data class PostState(
    val postId: String,
    val feedId: String,
    val headVersionId: String,
    val text: String,
    val imageHashes: List<String>,
    val imageTitles: List<String> = emptyList(),
    val deleted: Boolean,
    val conflicted: Boolean,
    val created: Hlc,
    val updated: Hlc,
)
