package de.beardedskunk.clipsharing

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import de.beardedskunk.clipsharing.data.Feed
import de.beardedskunk.clipsharing.data.FeedRepository
import de.beardedskunk.clipsharing.ui.FeedListScreen
import de.beardedskunk.clipsharing.ui.FeedScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = appGraph.repo
        setContent {
            ClipTheme {
                Surface {
                    AppRoot(repo)
                }
            }
        }
    }
}

@Composable
fun ClipTheme(content: @Composable () -> Unit) {
    MaterialTheme(content = content)
}

/** Einfache zustandsbasierte Navigation ohne zusaetzliche Navigationsbibliothek. */
@Composable
fun AppRoot(repo: FeedRepository) {
    var openFeed by remember { mutableStateOf<Feed?>(null) }
    val feed = openFeed
    if (feed == null) {
        FeedListScreen(repo = repo, onOpenFeed = { openFeed = it })
    } else {
        FeedScreen(repo = repo, feed = feed, onBack = { openFeed = null })
    }
}
