package hu.laurel.sqlpulse.ui.explain

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hu.laurel.sqlpulse.R
import hu.laurel.sqlpulse.data.sql.CellValue
import hu.laurel.sqlpulse.data.sql.ExplainAdvice
import hu.laurel.sqlpulse.data.sql.ExplainJson
import hu.laurel.sqlpulse.data.sql.ExplainNodeKind
import hu.laurel.sqlpulse.data.sql.ExplainNote
import hu.laurel.sqlpulse.data.sql.ExplainPlan
import hu.laurel.sqlpulse.data.sql.ExplainPlanFlag
import hu.laurel.sqlpulse.data.sql.ExplainPlanNode
import hu.laurel.sqlpulse.data.sql.ExplainPlanResult
import hu.laurel.sqlpulse.data.sql.ResultTable
import hu.laurel.sqlpulse.ui.components.InfoBadge
import hu.laurel.sqlpulse.ui.theme.LocalSemanticColors
import hu.laurel.sqlpulse.ui.theme.MonoStyles
import hu.laurel.sqlpulse.ui.theme.Spacing

/**
 * The plan of a query, drawn as the tree it actually is.
 *
 * Meant to sit above the result of an `EXPLAIN`, wherever that result is shown. It is given the
 * table the server returned and decides for itself what it is looking at: a one-cell JSON plan
 * becomes a tree, and anything else — an older server, a plan that did not parse, a plain
 * `EXPLAIN` — falls back to the warnings [ExplainAdvice] reads out of the table. The fallback is
 * the point: a phone in a server room is the worst place to discover that a screen only works
 * against one version of MySQL.
 */
@Composable
fun ExplainPlanSection(
    result: ResultTable,
    modifier: Modifier = Modifier,
) {
    val parsed = remember(result) { ExplainJson.of(jsonPlanOf(result)) }
    when (parsed) {
        is ExplainPlanResult.Parsed -> ExplainPlanTree(parsed.plan, modifier)
        is ExplainPlanResult.Unavailable -> {
            val notes = remember(result) { ExplainAdvice.of(result) }
            TabularAdvice(notes, showFallbackNote = looksLikeJson(result), modifier = modifier)
        }
    }
}

/**
 * The single JSON document an `EXPLAIN FORMAT=JSON` returns, or null.
 *
 * One row, one column, and MySQL calls it `EXPLAIN`. The column name is not checked: MariaDB and
 * the older driver label it differently, and a cell that starts with `{` is not going to be
 * anything but a plan.
 */
fun jsonPlanOf(table: ResultTable): String? {
    if (table.rows.size != 1 || table.columns.size != 1) return null
    val cell = table.rows.first().firstOrNull()
    val text = when (cell) {
        is CellValue.Text -> cell.value
        else -> return null
    }
    return text.takeIf { it.trimStart().startsWith("{") }
}

private fun looksLikeJson(table: ResultTable): Boolean = jsonPlanOf(table) != null

/** The warnings read out of a tabular plan: what the app has always shown, unchanged. */
@Composable
private fun TabularAdvice(
    notes: List<ExplainNote>,
    showFallbackNote: Boolean,
    modifier: Modifier = Modifier,
) {
    if (notes.isEmpty() && !showFallbackNote) return
    val semantic = LocalSemanticColors.current
    Column(modifier = modifier.padding(horizontal = Spacing.l, vertical = Spacing.xs)) {
        if (showFallbackNote) {
            Text(
                text = stringResource(R.string.plan_fallback),
                style = MaterialTheme.typography.bodySmall,
                color = semantic.textSecondary,
            )
        }
        notes.forEach { note ->
            Text(
                text = stringResource(note.textRes()),
                style = MaterialTheme.typography.bodySmall,
                color = semantic.warning,
            )
        }
    }
}

/**
 * The tree itself.
 *
 * Every node is collapsible and the plan opens with the expensive branch already unfolded, which
 * is the one thing somebody who ran `EXPLAIN` came to see. Everything else starts closed: a join
 * of six tables with subqueries does not fit on a phone, and a screenful of evenly weighted rows
 * is no easier to read than the table it replaced.
 */
@Composable
fun ExplainPlanTree(
    plan: ExplainPlan,
    modifier: Modifier = Modifier,
) {
    val semantic = LocalSemanticColors.current
    // The branch, plus the root, plus whatever the user opens afterwards. Held by node id, which
    // is the node's path in the tree and so survives the recomposition but not a new plan.
    var expanded by remember(plan) {
        mutableStateOf(plan.expensiveBranch.toSet() + plan.root.id + plan.root.children.map { it.id })
    }

    Column(modifier = modifier.padding(horizontal = Spacing.l, vertical = Spacing.s)) {
        Text(
            text = stringResource(R.string.plan_title),
            style = MaterialTheme.typography.titleSmall,
        )
        plan.totalCost?.let { cost ->
            Text(
                text = stringResource(R.string.plan_total_cost, cost.asCost()),
                style = MaterialTheme.typography.bodySmall,
                color = semantic.textSecondary,
            )
        }
        if (!plan.hasCostInfo) {
            Text(
                text = stringResource(R.string.plan_no_cost),
                style = MaterialTheme.typography.bodySmall,
                color = semantic.textSecondary,
            )
        }

        PlanNodeRows(
            node = plan.root,
            depth = 0,
            plan = plan,
            expanded = expanded,
            onToggle = { id -> expanded = if (id in expanded) expanded - id else expanded + id },
        )

        plan.notes.forEach { note ->
            Text(
                text = stringResource(note.textRes()),
                style = MaterialTheme.typography.bodySmall,
                color = semantic.warning,
                modifier = Modifier.padding(top = Spacing.xs),
            )
        }
    }
}

/** One node and, when it is open, everything under it. */
@Composable
private fun PlanNodeRows(
    node: ExplainPlanNode,
    depth: Int,
    plan: ExplainPlan,
    expanded: Set<String>,
    onToggle: (String) -> Unit,
) {
    val semantic = LocalSemanticColors.current
    val isOpen = node.id in expanded
    val onBranch = node.id in plan.expensiveBranch
    val isHeaviest = node.id == plan.expensiveNodeId

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (depth * 12).dp, top = Spacing.xs)
            .clip(RoundedCornerShape(12.dp))
            // Every step is a card; only the heaviest is filled in: a whole branch in colour
            // would say "look here" about five rows at once, which is the same as saying nothing.
            .background(if (isHeaviest) semantic.danger.copy(alpha = 0.13f) else MaterialTheme.colorScheme.surface)
            .border(
                1.dp,
                if (isHeaviest) semantic.danger else semantic.hairline,
                RoundedCornerShape(12.dp),
            )
            .clickable(enabled = node.children.isNotEmpty()) { onToggle(node.id) }
            .padding(horizontal = Spacing.s, vertical = Spacing.s),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (node.children.isNotEmpty()) {
                Icon(
                    imageVector = if (isOpen) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                    contentDescription = stringResource(
                        if (isOpen) R.string.plan_collapse else R.string.plan_expand,
                    ),
                    modifier = Modifier.size(18.dp),
                    tint = semantic.textSecondary,
                )
            } else {
                Spacer(modifier = Modifier.size(18.dp))
            }
            Text(
                text = stringResource(node.kind.labelRes()),
                style = MaterialTheme.typography.labelMedium,
                color = when {
                    isHeaviest -> semantic.danger
                    onBranch -> semantic.warning
                    else -> semantic.textSecondary
                },
            )
            Text(
                text = " ${node.label}",
                style = MonoStyles.cell,
                fontWeight = if (isHeaviest) FontWeight.Bold else FontWeight.Normal,
            )
        }

        val facts = node.facts()
        if (facts.isNotEmpty()) {
            Row(
                modifier = Modifier.padding(start = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                facts.forEach { (labelRes, value) ->
                    Text(
                        text = "${stringResource(labelRes)} $value",
                        style = MaterialTheme.typography.labelSmall,
                        color = semantic.textSecondary,
                    )
                }
            }
        }

        if (isHeaviest) {
            InfoBadge(
                text = stringResource(R.string.plan_expensive_badge),
                color = semantic.danger,
                modifier = Modifier.padding(start = 18.dp, top = Spacing.xs),
            )
        }

        node.flags.mapNotNull { it.textRes() }.forEach { textRes ->
            Text(
                text = stringResource(textRes),
                style = MaterialTheme.typography.labelSmall,
                color = semantic.textSecondary,
                modifier = Modifier.padding(start = 18.dp),
            )
        }

        node.message?.let { message ->
            Text(
                text = message,
                style = MonoStyles.cell,
                color = semantic.textSecondary,
                modifier = Modifier.padding(start = 18.dp),
            )
        }

        // The condition last and only when the node is open: it is the longest thing on the row
        // and the least often needed, and MySQL's rewritten form of it wraps over three lines.
        if (isOpen) {
            node.attachedCondition?.let { condition ->
                Text(
                    text = "${stringResource(R.string.plan_condition)}: $condition",
                    style = MonoStyles.cell,
                    color = semantic.textSecondary,
                    modifier = Modifier.padding(start = 18.dp),
                )
            }
        }
    }

    if (isOpen) {
        node.children.forEach { child ->
            PlanNodeRows(
                node = child,
                depth = depth + 1,
                plan = plan,
                expanded = expanded,
                onToggle = onToggle,
            )
        }
    }
}

/** The numbers worth putting on the row itself, in the order they answer "is this step the one". */
private fun ExplainPlanNode.facts(): List<Pair<Int, String>> = buildList {
    accessType?.let { add(R.string.plan_label_access to it) }
    when {
        usedKey != null -> add(R.string.plan_label_key to usedKey)
        kind == ExplainNodeKind.TABLE -> add(R.string.plan_label_no_key to "")
    }
    rowsExamined?.let { add(R.string.plan_label_rows_examined to it.grouped()) }
    rowsProduced?.let { add(R.string.plan_label_rows_produced to it.grouped()) }
    filteredPercent?.let { add(R.string.plan_label_filtered to "${it.asCost()}%") }
    cost?.let { add(R.string.plan_label_cost to it.asCost()) }
}

private fun Long.grouped(): String = "%,d".format(this)

private fun Double.asCost(): String = "%,.2f".format(this)

@StringRes
private fun ExplainNodeKind.labelRes(): Int = when (this) {
    ExplainNodeKind.QUERY_BLOCK -> R.string.plan_node_query_block
    ExplainNodeKind.TABLE -> R.string.plan_node_table
    ExplainNodeKind.NESTED_LOOP -> R.string.plan_node_nested_loop
    ExplainNodeKind.ORDERING -> R.string.plan_node_ordering
    ExplainNodeKind.GROUPING -> R.string.plan_node_grouping
    ExplainNodeKind.DUPLICATES_REMOVAL -> R.string.plan_node_distinct
    ExplainNodeKind.UNION -> R.string.plan_node_union
    ExplainNodeKind.SUBQUERY -> R.string.plan_node_subquery
    ExplainNodeKind.MATERIALISED -> R.string.plan_node_materialised
}

/**
 * Only the flags the numbers on the row do not already say.
 *
 * A full scan is visible from `access ALL` and is already in the warnings under the tree; saying
 * it a third time on the row would push the things that are not obvious off the screen.
 */
private fun ExplainPlanFlag.textRes(): Int? = when (this) {
    ExplainPlanFlag.COVERING_INDEX -> R.string.plan_flag_covering
    ExplainPlanFlag.JOIN_BUFFER -> R.string.plan_flag_join_buffer
    ExplainPlanFlag.DEPENDENT -> R.string.plan_flag_dependent
    ExplainPlanFlag.FULL_TABLE_SCAN,
    ExplainPlanFlag.FULL_INDEX_SCAN,
    ExplainPlanFlag.NO_INDEX,
    ExplainPlanFlag.FILESORT,
    ExplainPlanFlag.TEMPORARY_TABLE,
    -> null
}

/** The sentences the app already uses for these, shared with the tabular reading. */
@StringRes
private fun ExplainNote.textRes(): Int = when (this) {
    ExplainNote.FULL_TABLE_SCAN -> R.string.explain_full_scan
    ExplainNote.FULL_INDEX_SCAN -> R.string.explain_index_scan
    ExplainNote.NO_INDEX -> R.string.explain_no_index
    ExplainNote.FILESORT -> R.string.explain_filesort
    ExplainNote.TEMPORARY_TABLE -> R.string.explain_temporary
    ExplainNote.MANY_ROWS -> R.string.explain_many_rows
}
