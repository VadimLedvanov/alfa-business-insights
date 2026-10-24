package alpha.business.insight.core.qa.suites;

import org.junit.platform.suite.api.ExcludeTags;
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.IncludeTags;
import org.junit.platform.suite.api.SelectPackages;
import org.junit.platform.suite.api.Suite;
import org.junit.platform.suite.api.SuiteDisplayName;

/** Запускает полноценные E2E-тесты без smoke-сценариев. */
@Suite
@SuiteDisplayName("E2E-тесты backend")
@SelectPackages("alpha.business.insight.core")
@IncludeEngines("junit-jupiter")
@IncludeTags("e2e")
@ExcludeTags("smoke")
public class E2eSuite {
}
