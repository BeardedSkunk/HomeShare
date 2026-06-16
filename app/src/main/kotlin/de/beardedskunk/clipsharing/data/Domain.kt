package de.beardedskunk.clipsharing.data

import de.beardedskunk.clipsharing.core.Hlc

/** Ein benannter Feed. */
data class Feed(
    val id: String,
    val name: String,
    val created: Hlc,
    val deleted: Boolean = false,
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
    val deleted: Boolean,
    val conflicted: Boolean,
    val created: Hlc,
    val updated: Hlc,
)
