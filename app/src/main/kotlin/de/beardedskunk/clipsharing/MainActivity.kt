package de.beardedskunk.clipsharing

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/**
 * Platzhalter-Einstieg, nur zur Verifikation der Toolchain.
 * Wird in Phase 1 durch die Compose-UI (Feed-Liste) ersetzt.
 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tv = TextView(this).apply {
            text = "ClipSharing — Setup OK"
            textSize = 20f
            setPadding(48, 48, 48, 48)
        }
        setContentView(tv)
    }
}
