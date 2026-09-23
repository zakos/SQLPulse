package hu.laurel.sqlpulse.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hu.laurel.sqlpulse.data.connection.ConnectionEnvironment
import hu.laurel.sqlpulse.ui.theme.LocalSemanticColors
import hu.laurel.sqlpulse.ui.theme.MonoStyles
import hu.laurel.sqlpulse.ui.theme.Spacing

/**
 * The colour an environment is named in everywhere: development the accent, test amber,
 * production red. Unclassified connections have none, rather than borrowing one.
 */
@Composable
fun ConnectionEnvironment.color(): Color? = when (this) {
    ConnectionEnvironment.DEVELOPMENT -> MaterialTheme.colorScheme.primary
    ConnectionEnvironment.TEST -> LocalSemanticColors.current.warning
    ConnectionEnvironment.PRODUCTION -> LocalSemanticColors.current.production
    ConnectionEnvironment.UNSET -> null
}

/**
 * The title of every screen that works inside a connection: its name after a dot in the
 * environment's colour, and under it the database, which is a tap away from being another one.
 * Which server and which database are the two things to know before anything is run.
 */
@Composable
fun ConnectionTitle(
    name: String,
    environment: ConnectionEnvironment,
    database: String?,
    databases: List<String>,
    onSelectDatabase: (String) -> Unit,
    placeholder: String,
) {
    val semantic = LocalSemanticColors.current
    var open by remember { mutableStateOf(false) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            environment.color()?.let { Box(Modifier.size(8.dp).background(it, CircleShape)) }
            Text(
                name,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(enabled = databases.isNotEmpty(), role = Role.DropdownList) { open = true },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    database ?: placeholder,
                    style = MonoStyles.cell.copy(fontSize = 12.sp),
                    color = semantic.textSecondary,
                )
                if (databases.isNotEmpty()) {
                    Icon(
                        Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = semantic.textSecondary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                databases.forEach { candidate ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                candidate,
                                style = MonoStyles.cell,
                                color = if (candidate == database) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                        },
                        onClick = {
                            open = false
                            onSelectDatabase(candidate)
                        },
                    )
                }
            }
        }
    }
}
