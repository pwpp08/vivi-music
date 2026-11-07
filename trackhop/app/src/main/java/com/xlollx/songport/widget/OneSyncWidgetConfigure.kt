package com.xlollx.songport.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.xlollx.songport.R
import com.xlollx.songport.data.Store
import com.xlollx.songport.ui.AppTheme
import com.xlollx.songport.ui.ThemeMode
import kotlinx.coroutines.launch

/** Opens when a "One sync" widget is placed: the user picks which sync it shows. */
class OneSyncWidgetConfigure : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val widgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // Backing out places no widget.
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return finish()
        val rows = Widgets.rows(this)
        val mode = ThemeMode.of(Store.get(this).data.settings.themeMode)
        setContent {
            AppTheme(mode, dynamicColor = true) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.th_widget_pick_sync), style = MaterialTheme.typography.headlineSmall)
                        if (rows.isEmpty()) Text(stringResource(R.string.th_widget_no_syncs), style = MaterialTheme.typography.bodyMedium)
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(rows, key = { it.id }) { r ->
                                Card(Modifier.fillMaxWidth().clickable { choose(widgetId, r.id) }) {
                                    Column(Modifier.padding(16.dp)) {
                                        Text(r.name, style = MaterialTheme.typography.titleMedium)
                                        Text(r.status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun choose(widgetId: Int, jobId: String) {
        lifecycleScope.launch {
            chooseSyncForWidget(applicationContext, widgetId, jobId)
            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
            finish()
        }
    }
}
