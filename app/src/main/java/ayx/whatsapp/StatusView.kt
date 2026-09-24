package ayx.whatsapp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

/**
 * Bottom sheet listing who viewed the user's status. Fetches read
 * receipts from the gateway (/status/viewers) for the given status ids
 * and resolves each viewer to a device-contact name where possible.
 */
@Composable
fun StatusViewersSheet(statusIds: List<String>, onClose: () -> Unit) {
    var viewers by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(statusIds) {
        loading = true
        viewers = GatewayClient.getStatusViewers(statusIds)
        loading = false
    }
    Dialog(onDismissRequest = onClose) {
        Surface(shape = RoundedCornerShape(18.dp), color = Color(0xFF1C1C1E)) {
            Column(Modifier.padding(18.dp).fillMaxWidth().heightIn(max = 480.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Visibility, null, tint = Color.White)
                    Spacer(Modifier.width(10.dp))
                    Text("Viewed by " + viewers.size, color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.size(14.dp))
                if (loading) {
                    Box(Modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color.White) }
                } else if (viewers.isEmpty()) {
                    Text("No views yet", color = Color.White.copy(alpha = 0.6f), modifier = Modifier.padding(vertical = 20.dp))
                } else {
                    LazyColumn(Modifier.fillMaxWidth()) {
                        items(viewers) { pair ->
                            val jid = pair.first
                            val num = jid.substringBefore("@").substringBefore(":")
                            val resolved = ContactStore.nameFor(jid) ?: ""
                            val name = if (resolved.isNotBlank()) resolved else if (pair.second.isNotBlank()) pair.second else num
                            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(42.dp).clip(CircleShape).background(Color(0xFF0A84FF).copy(alpha = 0.30f)), contentAlignment = Alignment.Center) {
                                    Text(name.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold)
                                }
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(name, color = Color.White)
                                    if (name != num) Text(num, color = Color.White.copy(alpha = 0.5f), style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
