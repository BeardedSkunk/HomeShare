# R8/Minify-Regeln. Debug- UND Release-Build sind minifyEnabled (Debug-APKs werden ausgerollt,
# und material-icons-extended muss auf die genutzten Icons getreeshaked werden).

# --- Standard-Enum-Methoden behalten ---
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# --- Enums, deren KONSTANTEN-NAMEN serialisiert werden (canonical/versionId-Hash, Wire-Codec,
#     DB-Spalten, FRITZ-JSON). Wuerde R8 sie umbenennen, aenderten sich Hashes/Format -> Bruch. ---
-keep class de.beardedskunk.homeshare.core.NodeType { *; }
-keep class de.beardedskunk.homeshare.core.NodeKind { *; }
-keep class de.beardedskunk.homeshare.data.FeedRight { *; }
-keep class de.beardedskunk.homeshare.data.Recurrence { *; }

# --- NanoHTTPD (eingebetteter Web-Server) ---
-keep class fi.iki.elonen.** { *; }
-dontwarn fi.iki.elonen.**

# --- commons-net FTP (FRITZ!Box-Replik): die FTPFileEntryParserFactory laedt Parser per
#     Reflection/Class.forName -> Klassen behalten. ---
-keep class org.apache.commons.net.** { *; }
-dontwarn org.apache.commons.net.**
-dontwarn javax.**

# --- ZXing-Scanner (per Manifest CaptureActivity referenziert) + QR-Encoder ---
-keep class com.journeyapps.barcodescanner.** { *; }
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**
