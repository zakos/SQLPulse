package hu.laurel.sqlpulse.data.csv

import hu.laurel.sqlpulse.data.schema.SchemaColumn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CsvMappingTest {
    private fun column(name: String, type: String, nullable: Boolean = true, default: String? = null, extra: String? = null) =
        SchemaColumn(name, type, nullable, default, isPrimaryKey = false, extra = extra, comment = null)

    private val columns = listOf(
        column("id", "int(11)", nullable = false, extra = "auto_increment"),
        column("name", "varchar(80)", nullable = false),
        column("qty", "int(11)"),
        column("price", "decimal(10,2)"),
        column("sold_at", "datetime"),
    )

    private fun plan(header: List<String>, rows: List<List<String?>>): ImportPlan {
        val table = CsvTable(header, rows, 0)
        return ImportPlan(table, CsvImport.match(header, columns), ',', columns = columns)
    }

    @Test
    fun `the by-name match is where the mapping starts`() {
        val p = plan(listOf("Name", "extra"), listOf(listOf("a", "x")))
        assertEquals(listOf("name", null), p.mapping)
        assertTrue(p.canImport)
    }

    @Test
    fun `remapping sends a file column to another table column or skips it`() {
        val p = plan(listOf("Name", "extra"), listOf(listOf("a", "5")))
        val moved = CsvMapping.remap(p, 1, "qty")
        assertEquals(listOf("name", "qty"), moved.mapping)
        assertEquals(mapOf("Name" to "name", "extra" to "qty"), moved.match.matched)
        assertTrue(moved.match.unmatched.isEmpty())
        val skipped = CsvMapping.remap(moved, 0, null)
        assertEquals(listOf("Name"), skipped.match.unmatched)
    }

    @Test
    fun `a required column without a source blocks the import`() {
        val p = plan(listOf("qty"), listOf(listOf("1")))
        assertFalse(p.canImport)
        val issue = p.issues.single { it.kind == MappingIssueKind.REQUIRED_UNMAPPED }
        assertEquals("name", issue.tableColumn)
        // Mapping the file's column onto it fixes that.
        assertTrue(CsvMapping.remap(p, 0, "name").canImport)
    }

    @Test
    fun `an auto increment key and nullable columns are not required`() {
        val p = plan(listOf("name"), listOf(listOf("a")))
        assertTrue(p.issues.isEmpty())
    }

    @Test
    fun `two file columns into one table column blocks`() {
        val p = CsvMapping.remap(plan(listOf("name", "qty"), listOf(listOf("a", "1"))), 1, "name")
        assertFalse(p.canImport)
        val issue = p.issues.single { it.kind == MappingIssueKind.DUPLICATE_TARGET }
        assertEquals("name, qty", issue.fileColumn)
        assertEquals("name", issue.tableColumn)
    }

    @Test
    fun `nothing mapped blocks with one clear issue`() {
        val p = plan(listOf("zzz"), listOf(listOf("a")))
        assertEquals(listOf(MappingIssueKind.NOTHING_MAPPED), p.issues.map { it.kind })
        assertFalse(p.canImport)
    }

    @Test
    fun `text into an integer column is a warning with a count and an example`() {
        val p = plan(listOf("name", "qty"), listOf(listOf("a", "1"), listOf("b", "many"), listOf("c", "x"), listOf("d", null)))
        val issue = p.issues.single { it.kind == MappingIssueKind.TYPE_MISMATCH }
        assertEquals("qty", issue.tableColumn)
        assertEquals(2, issue.count)
        assertEquals("many", issue.example)
        assertEquals("int(11)", issue.typeName)
        // Only a warning: the person may know better.
        assertTrue(p.canImport)
    }

    @Test
    fun `NULLs into a NOT NULL column warn but a nullable one does not`() {
        val p = plan(listOf("name", "qty"), listOf(listOf("a", null), listOf(null, "1"), listOf("c", null)))
        val issue = p.issues.single { it.kind == MappingIssueKind.NULL_IN_REQUIRED }
        assertEquals("name", issue.tableColumn)
        assertEquals(1, issue.count)
    }

    @Test
    fun `blocking issues are listed before warnings`() {
        val p = plan(listOf("qty"), listOf(listOf("many")))
        assertEquals(listOf(MappingIssueKind.REQUIRED_UNMAPPED, MappingIssueKind.TYPE_MISMATCH), p.issues.map { it.kind })
    }

    @Test
    fun `type kinds cover the spellings of every engine`() {
        assertEquals(TypeKind.INTEGER, TypeKind.of("bigint unsigned"))
        assertEquals(TypeKind.INTEGER, TypeKind.of("int4"))
        assertEquals(TypeKind.DECIMAL, TypeKind.of("numeric(10,2)"))
        assertEquals(TypeKind.DECIMAL, TypeKind.of("double precision"))
        assertEquals(TypeKind.DATETIME, TypeKind.of("timestamp without time zone"))
        assertEquals(TypeKind.DATETIME, TypeKind.of("datetime2"))
        assertEquals(TypeKind.BOOLEAN, TypeKind.of("bit"))
        assertEquals(TypeKind.OTHER, TypeKind.of("varchar(20)"))
        assertEquals(TypeKind.OTHER, TypeKind.of("uuid"))
    }

    @Test
    fun `values are accepted as the server would take them`() {
        assertTrue(TypeKind.INTEGER.accepts(" -12 "))
        assertFalse(TypeKind.INTEGER.accepts("1.5"))
        assertFalse(TypeKind.INTEGER.accepts(""))
        assertTrue(TypeKind.DECIMAL.accepts("1.5e3"))
        assertFalse(TypeKind.DECIMAL.accepts("1,5"))
        assertTrue(TypeKind.BOOLEAN.accepts("TRUE"))
        assertTrue(TypeKind.DATETIME.accepts("2026-10-03 14:02:11"))
        assertTrue(TypeKind.DATETIME.accepts("2026-10-03T14:02:11Z"))
        assertTrue(TypeKind.DATE.accepts("2026-10-03"))
        assertFalse(TypeKind.DATE.accepts("03/10/2026"))
        assertTrue(TypeKind.TIME.accepts("12:30"))
        assertTrue(TypeKind.OTHER.accepts("anything"))
    }

    @Test
    fun `the preview shows the first three rows of the mapped columns only`() {
        val p = CsvMapping.remap(
            plan(listOf("n", "skip", "q"), List(5) { listOf("r$it", "s", "$it") }),
            2, "qty",
        ).let { CsvMapping.remap(it, 0, "name") }
        assertEquals(listOf("name", "qty"), CsvMapping.previewHeader(p))
        assertEquals(listOf(listOf("r0", "0"), listOf("r1", "1"), listOf("r2", "2")), CsvMapping.preview(p))
    }

    @Test
    fun `statements follow the mapping, not the header names`() {
        val statements = CsvImport.statements(
            "db", "t", listOf("name", null, "qty"), listOf(listOf("a", "ignored", "7")),
        )
        assertEquals(1, statements.size)
        assertEquals(listOf("a", "7"), statements.single().parameters)
        assertTrue(statements.single().sql.contains("`name`"))
        assertFalse(statements.single().sql.contains("ignored"))
    }
}
