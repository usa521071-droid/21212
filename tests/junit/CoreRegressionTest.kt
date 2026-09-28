import org.junit.Test

/** Uses the real org.json dependency on the host JVM, not Android's throwing stub. */
class CoreRegressionTest {
    @Test fun allRoleplayAndContextRegressions() = runCoreRegression()
}
