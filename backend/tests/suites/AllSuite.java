package alpha.business.insight.core.qa.suites;

import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectPackages;
import org.junit.platform.suite.api.Suite;
import org.junit.platform.suite.api.SuiteDisplayName;

/** Запускает все тесты backend на движке JUnit Jupiter. */
@Suite
@SuiteDisplayName("Все тесты backend")
@SelectPackages("alpha.business.insight.core")
@IncludeEngines("junit-jupiter")
public class AllSuite {
}
