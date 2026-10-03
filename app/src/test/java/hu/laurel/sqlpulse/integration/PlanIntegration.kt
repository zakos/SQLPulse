package hu.laurel.sqlpulse.integration

import hu.laurel.sqlpulse.data.settings.Settings
import hu.laurel.sqlpulse.data.settings.SettingsRepository
import hu.laurel.sqlpulse.data.sql.ExplainPlan
import hu.laurel.sqlpulse.data.sql.ExplainPlanResult
import hu.laurel.sqlpulse.data.sql.ParameterValue
import hu.laurel.sqlpulse.data.sql.QueryExecutor
import hu.laurel.sqlpulse.data.sql.SqlSessionManager
import hu.laurel.sqlpulse.data.sql.dialect.SqlDialect
import hu.laurel.sqlpulse.data.sql.plan.PlanReaders
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

/**
 * What the editor's EXPLAIN button does, minus the view model: the dialect's explain statement
 * through the real [QueryExecutor] (its guards, the plan mode of SQL Server) on a real session,
 * then the reader the plan screen would pick for the rows that come back.
 */
object PlanIntegration {

    fun executor(manager: SqlSessionManager): QueryExecutor {
        val settings = mockk<SettingsRepository>()
        every { settings.settings } returns flowOf(Settings())
        // The integration manager is a mock that knows only what the repositories ask of it; the
        // executor also asks whether the user has a transaction open.
        every { manager.inTransaction } returns MutableStateFlow(false)
        return QueryExecutor(
            manager, mockk(relaxed = true), settings, mockk(relaxed = true), mockk(relaxed = true),
            Dispatchers.Unconfined,
        )
    }

    /** Explains [sql] the way the editor does and reads the answer; fails the test if it is no plan. */
    fun plan(
        executor: QueryExecutor,
        dialect: SqlDialect,
        sql: String,
        parameters: Map<String, ParameterValue> = emptyMap(),
    ): ExplainPlan {
        val outcome = runBlocking {
            executor.run(connectionId = 7, sql = checkNotNull(dialect.explain(sql)), parameters = parameters, readOnly = false)
        }
        // The plan screen has no engine to ask, only the rows: the shape must be recognised.
        val reader = PlanReaders.detect(outcome.table)
        assertNotNull("no reader recognised the answer of ${dialect.engine}: ${outcome.table.columns}", reader)
        assertTrue("the dialect's own reader differs from the detected one", reader == dialect.planReader)
        val result = reader!!.read(outcome.table)
        assertTrue("not a plan: $result", result is ExplainPlanResult.Parsed)
        return (result as ExplainPlanResult.Parsed).plan
    }
}
