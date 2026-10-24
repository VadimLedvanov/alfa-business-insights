package alpha.business.insight.core.qa.suites;

import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.IncludeTags;
import org.junit.platform.suite.api.SelectPackages;
import org.junit.platform.suite.api.Suite;
import org.junit.platform.suite.api.SuiteDisplayName;

/** Запускает smoke-тесты backend. */
@Suite
@SuiteDisplayName("Smoke-тесты backend")
@SelectPackages("alpha.business.insight.core")
@IncludeEngines("junit-jupiter")
@IncludeTags("smoke")
public class SmokeSuite {}
