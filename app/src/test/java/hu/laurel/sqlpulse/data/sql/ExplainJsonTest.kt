package hu.laurel.sqlpulse.data.sql

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The plans below are real `EXPLAIN FORMAT=JSON` output shapes, trimmed of the fields the app
 * never reads (`used_columns`, `key_length`, `ref`) but otherwise spelled exactly as the servers
 * spell them — including MySQL's habit of quoting the cost figures and not the row counts.
 */
class ExplainJsonTest {

    /** MySQL 8.0, one table, no index: the simplest plan that is worth a warning. */
    private val singleTableScan = """
        {
          "query_block": {
            "select_id": 1,
            "cost_info": { "query_cost": "101025.65" },
            "table": {
              "table_name": "orders",
              "access_type": "ALL",
              "rows_examined_per_scan": 998110,
              "rows_produced_per_join": 99811,
              "filtered": "10.00",
              "cost_info": {
                "read_cost": "91044.55",
                "eval_cost": "9981.10",
                "prefix_cost": "101025.65",
                "data_read_per_join": "22M"
              },
              "attached_condition": "(`shop`.`orders`.`status` = 'open')"
            }
          }
        }
    """.trimIndent()

    /**
     * MySQL 8.0, three tables joined and sorted afterwards. The driving table is scanned in full
     * and the two lookups are cheap, which is exactly the case the highlight exists for.
     */
    private val nestedLoopWithSort = """
        {
          "query_block": {
            "select_id": 1,
            "cost_info": { "query_cost": "134502.20" },
            "ordering_operation": {
              "using_filesort": true,
              "cost_info": { "sort_cost": "9981.10" },
              "nested_loop": [
                {
                  "table": {
                    "table_name": "orders",
                    "access_type": "ALL",
                    "possible_keys": ["idx_customer", "idx_status"],
                    "rows_examined_per_scan": 998110,
                    "rows_produced_per_join": 99811,
                    "filtered": "10.00",
                    "cost_info": {
                      "read_cost": "91044.55",
                      "eval_cost": "9981.10",
                      "prefix_cost": "101025.65"
                    },
                    "attached_condition": "(`shop`.`orders`.`status` = 'open')"
                  }
                },
                {
                  "table": {
                    "table_name": "customers",
                    "access_type": "eq_ref",
                    "possible_keys": ["PRIMARY"],
                    "key": "PRIMARY",
                    "rows_examined_per_scan": 1,
                    "rows_produced_per_join": 99811,
                    "filtered": "100.00",
                    "cost_info": {
                      "read_cost": "12476.37",
                      "eval_cost": "9981.10",
                      "prefix_cost": "123483.12"
                    }
                  }
                },
                {
                  "table": {
                    "table_name": "addresses",
                    "access_type": "ref",
                    "possible_keys": ["idx_customer"],
                    "key": "idx_customer",
                    "using_index": true,
                    "rows_examined_per_scan": 2,
                    "rows_produced_per_join": 199622,
                    "filtered": "100.00",
                    "cost_info": {
                      "read_cost": "1.00",
                      "eval_cost": "19962.20",
                      "prefix_cost": "124521.10"
                    }
                  }
                }
              ]
            }
          }
        }
    """.trimIndent()

    /**
     * A plan with no cost information anywhere: MariaDB words it this way, and so does MySQL when
     * the cost model is unavailable. Everything below `cost_info` has to keep working without it.
     */
    private val withoutCostInfo = """
        {
          "query_block": {
            "select_id": 1,
            "nested_loop": [
              {
                "table": {
                  "table_name": "invoices",
                  "access_type": "index",
                  "key": "idx_issued",
                  "rows": 4200,
                  "filtered": "100.00"
                }
              },
              {
                "table": {
                  "table_name": "invoice_lines",
                  "access_type": "ALL",
                  "rows": 250000,
                  "filtered": "100.00",
                  "attached_condition": "(`billing`.`invoice_lines`.`invoice_id` = `billing`.`invoices`.`id`)"
                }
              }
            ]
          }
        }
    """.trimIndent()

    @Test
    fun `reads a single table plan`() {
        val plan = parsed(singleTableScan)

        assertEquals(ExplainNodeKind.QUERY_BLOCK, plan.root.kind)
        assertEquals("select #1", plan.root.label)
        assertEquals(101025.65, plan.totalCost!!, 0.01)
        assertTrue(plan.hasCostInfo)

        val table = plan.root.children.single()
        assertEquals(ExplainNodeKind.TABLE, table.kind)
        assertEquals("orders", table.label)
        assertEquals("ALL", table.accessType)
        assertNull(table.usedKey)
        assertEquals(998110L, table.rowsExamined)
        assertEquals(99811L, table.rowsProduced)
        assertEquals(10.0, table.filteredPercent!!, 0.001)
        // read_cost + eval_cost, not prefix_cost.
        assertEquals(101025.65, table.cost!!, 0.01)
        assertEquals("(`shop`.`orders`.`status` = 'open')", table.attachedCondition)
        assertTrue(ExplainPlanFlag.FULL_TABLE_SCAN in table.flags)
        assertTrue(ExplainPlanFlag.NO_INDEX in table.flags)
    }

    @Test
    fun `warns about the same things as the tabular reading`() {
        val notes = parsed(singleTableScan).notes

        assertTrue(ExplainNote.FULL_TABLE_SCAN in notes)
        assertTrue(ExplainNote.NO_INDEX in notes)
        assertTrue(ExplainNote.MANY_ROWS in notes)
        assertTrue(ExplainNote.FILESORT !in notes)
    }

    @Test
    fun `flattens a nested loop into one child per table`() {
        val plan = parsed(nestedLoopWithSort)

        val ordering = plan.root.children.single()
        assertEquals(ExplainNodeKind.ORDERING, ordering.kind)
        assertTrue(ExplainPlanFlag.FILESORT in ordering.flags)
        assertEquals(9981.10, ordering.cost!!, 0.01)

        val loop = ordering.children.single()
        assertEquals(ExplainNodeKind.NESTED_LOOP, loop.kind)
        assertEquals(
            listOf("orders", "customers", "addresses"),
            loop.children.map { it.label },
        )
        // The element wrappers of the nested_loop array are not steps and get no nodes.
        assertTrue(loop.children.all { it.children.isEmpty() })
        assertTrue(ExplainPlanFlag.COVERING_INDEX in loop.children[2].flags)
        assertEquals("idx_customer", loop.children[2].usedKey)
        assertEquals(listOf("idx_customer", "idx_status"), loop.children[0].possibleKeys)
    }

    @Test
    fun `node ids are unique and rooted`() {
        val ids = parsed(nestedLoopWithSort).root.flatten().map { it.id }

        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.all { it == "0" || it.startsWith("0.") })
    }

    @Test
    fun `the expensive branch ends at the table that costs, not the last one joined`() {
        val plan = parsed(nestedLoopWithSort)
        val expensive = plan.root.flatten().single { it.id == plan.expensiveNodeId }

        assertEquals("orders", expensive.label)
        // The whole path is marked, so every ancestor can be drawn as part of the branch.
        assertEquals(listOf("0", "0.0", "0.0.0", "0.0.0.0"), plan.expensiveBranch)
    }

    @Test
    fun `a plan without cost information is still read, and ranked by rows`() {
        val plan = parsed(withoutCostInfo)

        assertTrue(!plan.hasCostInfo)
        assertNull(plan.totalCost)
        val loop = plan.root.children.single()
        assertEquals(2, loop.children.size)
        assertEquals(4200L, loop.children[0].rowsExamined)
        assertNull(loop.children[0].cost)

        // Rows are the only measure left, so the 250k scan is the expensive step.
        val expensive = plan.root.flatten().single { it.id == plan.expensiveNodeId }
        assertEquals("invoice_lines", expensive.label)
        assertTrue(ExplainNote.FULL_INDEX_SCAN in plan.notes)
        assertTrue(ExplainNote.FULL_TABLE_SCAN in plan.notes)
    }

    @Test
    fun `a plan with a single step highlights nothing`() {
        val plan = parsed(
            """{ "query_block": { "select_id": 1, "message": "No tables used" } }""",
        )

        assertEquals("No tables used", plan.root.message)
        assertTrue(plan.root.children.isEmpty())
        assertTrue(plan.expensiveBranch.isEmpty())
        assertNull(plan.expensiveNodeId)
    }

    @Test
    fun `reads a dependent subquery`() {
        val plan = parsed(
            """
            {
              "query_block": {
                "select_id": 1,
                "table": {
                  "table_name": "customers",
                  "access_type": "ALL",
                  "rows_examined_per_scan": 500,
                  "attached_condition": "exists(select 1)",
                  "attached_subqueries": [
                    {
                      "dependent": true,
                      "cacheable": false,
                      "query_block": {
                        "select_id": 2,
                        "table": {
                          "table_name": "orders",
                          "access_type": "ref",
                          "key": "idx_customer",
                          "rows_examined_per_scan": 3
                        }
                      }
                    }
                  ]
                }
              }
            }
            """.trimIndent(),
        )

        val table = plan.root.children.single()
        val subquery = table.children.single()
        assertEquals(ExplainNodeKind.SUBQUERY, subquery.kind)
        assertTrue(ExplainPlanFlag.DEPENDENT in subquery.flags)
        assertEquals("orders", subquery.children.single().label)
    }

    @Test
    fun `reads a union`() {
        val plan = parsed(
            """
            {
              "query_block": {
                "union_result": {
                  "using_temporary_table": true,
                  "table_name": "<union1,2>",
                  "query_specifications": [
                    {
                      "dependent": false,
                      "query_block": {
                        "select_id": 1,
                        "table": { "table_name": "archive", "access_type": "ALL", "rows_examined_per_scan": 10 }
                      }
                    },
                    {
                      "dependent": false,
                      "query_block": {
                        "select_id": 2,
                        "table": { "table_name": "live", "access_type": "ALL", "rows_examined_per_scan": 20 }
                      }
                    }
                  ]
                }
              }
            }
            """.trimIndent(),
        )

        val union = plan.root.children.single()
        assertEquals(ExplainNodeKind.UNION, union.kind)
        assertTrue(ExplainPlanFlag.TEMPORARY_TABLE in union.flags)
        assertEquals(2, union.children.size)
        assertEquals(
            listOf("archive", "live"),
            union.children.map { it.children.single().label },
        )
        assertTrue(ExplainNote.TEMPORARY_TABLE in plan.notes)
    }

    @Test
    fun `text that is not json falls back`() {
        val result = ExplainJson.of("1\tSIMPLE\torders\tALL\tNULL\tNULL")

        assertEquals(
            ExplainPlanResult.Unavailable(ExplainUnavailable.NOT_JSON),
            result,
        )
    }

    @Test
    fun `json that is not a plan falls back`() {
        assertEquals(
            ExplainPlanResult.Unavailable(ExplainUnavailable.NOT_A_PLAN),
            ExplainJson.of("""{ "rows": 3 }"""),
        )
        assertEquals(
            ExplainPlanResult.Unavailable(ExplainUnavailable.NOT_A_PLAN),
            ExplainJson.of("""[1, 2, 3]"""),
        )
    }

    @Test
    fun `nothing to read falls back to the tabular plan`() {
        assertEquals(
            ExplainPlanResult.Unavailable(ExplainUnavailable.UNSUPPORTED),
            ExplainJson.of(null),
        )
        assertEquals(
            ExplainPlanResult.Unavailable(ExplainUnavailable.UNSUPPORTED),
            ExplainJson.of("   "),
        )
    }

    @Test
    fun `a syntax error is what an old server says about FORMAT=JSON`() {
        assertTrue(
            ExplainJson.isUnsupported(
                1064,
                "You have an error in your SQL syntax; check the manual ... near 'FORMAT=JSON SELECT 1'",
            ),
        )
        // A missing table is not a reason to retry: it fails the same way without the format.
        assertTrue(!ExplainJson.isUnsupported(1146, "Table 'shop.nope' doesn't exist"))
    }

    private fun parsed(text: String): ExplainPlan {
        val result = ExplainJson.of(text)
        assertNotNull(result)
        return (result as ExplainPlanResult.Parsed).plan
    }
}
