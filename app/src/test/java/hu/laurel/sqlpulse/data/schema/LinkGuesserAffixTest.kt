package hu.laurel.sqlpulse.data.schema

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The schema that had no links on a real phone: MantisBT, which declares no foreign keys. */
class LinkGuesserAffixTest {

    private fun table(name: String, vararg columns: String, pk: List<String> = listOf("id")) =
        TableColumns(name, columns.toList(), pk)

    private val mantis = listOf(
        table("mantis_bug_table", "id", "project_id", "reporter_id", "handler_id", "duplicate_id", "category_id", "bug_text_id", "profile_id"),
        table("mantis_bug_text_table", "id", "description"),
        table("mantis_bug_file_table", "id", "bug_id", "user_id", "filename"),
        table("mantis_bugnote_table", "id", "bug_id", "reporter_id", "bugnote_text_id"),
        table("mantis_bugnote_text_table", "id", "note"),
        table("mantis_bug_history_table", "id", "user_id", "bug_id", "field_name"),
        table("mantis_bug_monitor_table", "user_id", "bug_id", pk = listOf("user_id", "bug_id")),
        table("mantis_bug_relationship_table", "id", "source_bug_id", "destination_bug_id"),
        table("mantis_category_table", "id", "project_id", "user_id", "name"),
        table("mantis_project_table", "id", "name"),
        table("mantis_project_user_list_table", "project_id", "user_id", pk = listOf("project_id", "user_id")),
        table("mantis_user_table", "id", "username"),
        table("mantis_user_profile_table", "id", "user_id"),
        table("mantis_news_table", "id", "project_id", "poster_id"),
        table("mantis_config_table", "config_id", "project_id", "user_id", pk = listOf("config_id", "project_id", "user_id")),
        table("mantis_tag_table", "id", "user_id"),
    )

    private fun links(tables: List<TableColumns>) =
        LinkGuesser.infer(tables).map { it.from to it.to }.toSet()

    @Test
    fun `a shared prefix and suffix are taken off before the names are matched`() {
        val found = links(mantis)
        assertTrue("bug_id -> bug", "mantis_bug_file_table" to "mantis_bug_table" in found)
        assertTrue("user_id -> user", "mantis_bug_file_table" to "mantis_user_table" in found)
        assertTrue("project_id", "mantis_bug_table" to "mantis_project_table" in found)
        assertTrue("category_id", "mantis_bug_table" to "mantis_category_table" in found)
        assertTrue("bug_text_id is the bug_text table", "mantis_bug_table" to "mantis_bug_text_table" in found)
        assertTrue("bugnote_text_id", "mantis_bugnote_table" to "mantis_bugnote_text_table" in found)
        assertTrue("mantis_bugnote_table" to "mantis_bug_table" in found)
        assertTrue("mantis_bug_history_table" to "mantis_bug_table" in found)
        assertTrue("mantis_bug_monitor_table" to "mantis_user_table" in found)
        assertTrue("mantis_project_user_list_table" to "mantis_project_table" in found)
        assertTrue("mantis_category_table" to "mantis_project_table" in found)
    }

    @Test
    fun `role columns point at the user table, and are still guesses`() {
        val edges = LinkGuesser.infer(mantis)
        val reporter = edges.single { it.from == "mantis_bug_table" && it.to == "mantis_user_table" }
        assertTrue(reporter.guessed)
        // reporter_id and handler_id are both users: one link, from the first column that said so.
        assertEquals(listOf("reporter_id"), reporter.columns)
        assertTrue(edges.all { it.guessed })
        assertTrue("mantis_bugnote_table" to "mantis_user_table" in links(mantis))
    }

    @Test
    fun `a role is read from the last word of a longer name`() {
        assertTrue("mantis_bug_relationship_table" to "mantis_bug_table" in links(mantis))
    }

    @Test
    fun `roles need a user table to point at`() {
        val withoutUsers = mantis.filterNot { it.table.contains("user") }
        val found = LinkGuesser.infer(withoutUsers)
        assertTrue(found.none { it.columns.any { c -> c == "reporter_id" || c == "handler_id" } })
    }

    @Test
    fun `nothing links to its own table or to a made up one`() {
        val edges = LinkGuesser.infer(mantis)
        assertTrue(edges.none { it.from == it.to })
        // duplicate_id, profile_id and poster_id have no table of that name.
        assertTrue(edges.none { it.columns == listOf("duplicate_id") })
        assertTrue(edges.none { it.columns == listOf("poster_id") })
    }

    @Test
    fun `an affix only counts when most tables carry it`() {
        // Two of five start with "app_": under the 60 % bar, so nothing is taken off and
        // x_id finds no table called "x".
        val tables = listOf(
            table("app_a", "id"),
            table("app_b", "id"),
            table("bolt_x", "id"),
            table("hivas", "id", "x_id"),
            table("bolt", "id"),
        )
        assertTrue(links(tables).isEmpty())
    }

    @Test
    fun `a prefix alone works, and the suffix is optional`() {
        val tables = listOf(
            table("wp_posts", "id", "author_id"),
            table("wp_users", "id"),
            table("wp_comments", "id", "post_id", "user_id"),
            table("wp_options", "id"),
        )
        assertEquals(
            setOf("wp_posts" to "wp_users", "wp_comments" to "wp_posts", "wp_comments" to "wp_users"),
            links(tables),
        )
    }
}
