package de.beardedskunk.homeshare.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

// Zentrale testTag-Vergabe. Die Tags erscheinen dank testTagsAsResourceId (MainActivity)
// als resource-id im uiautomator-Dump und machen UI-Elemente fuer automatisierte
// Pruefungen (adb) findbar, ohne im Screenshot suchen zu muessen.
//
// Konvention (kleingeschrieben, Doppelpunkt-getrennt, dynamischer Teil = 1. Titelzeile):
//   row:<titel>            Listen-/Post-/Kalender-/Todo-Zeilen
//   drag:<titel>           Drag-Handle einer Zeile
//   fab:add                Haupt-FAB
//   menu:create:<kind>     FAB-Typ-Menue (list|note|calendar|todo|image|file)
//   topbar:<aktion>        back|search|share|settings|delete|edit|save|add|listinfo|overflow|tags|close
//   action:<aktion>        Eintraege des Langdruck-Aktionsdialogs (tag-search|tag-remove|tag-create|…)
//   menu:<aktion>          Ueberlaufmenue (share|delete-list|delete-note|delete-attachment|delete-todo|delete-entry|calendar-sync|calendar-entry-sync|add-tag)
//   toolbar:<aktion>       Markdown-Toolbar
//   field:<name>           Eingabefelder (body|listbody|calbody|caption:<i>|search|subitem-add|find|tag)
//   tags:add               Plus-Button der Tag-Zeile
//   tagsel:<tag>           Gewaehltes Tag im Tag-Such-Screen (Chip mit X)
//   todo:done, box:subitems, box:attachments, box:event

fun Modifier.tag(name: String): Modifier = testTag(name)

/** Zeilen-Tag aus dem Knotentitel: erste Zeile, auf 40 Zeichen gekappt. */
fun rowTag(title: String): String = "row:" + title.lineSequence().firstOrNull().orEmpty().take(40)

fun dragTag(title: String): String = "drag:" + title.lineSequence().firstOrNull().orEmpty().take(40)
