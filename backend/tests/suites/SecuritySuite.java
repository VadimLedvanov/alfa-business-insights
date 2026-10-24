package alpha.business.insight.core.qa.suites;

import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.IncludeTags;
import org.junit.platform.suite.api.SelectPackages;
import org.junit.platform.suite.api.Suite;
import org.junit.platform.suite.api.SuiteDisplayName;

/** Запускает тесты аутентификации, ролей и контроля доступа. */
@Suite
@SuiteDisplayName("Тесты безопасности")
@SelectPackages("alpha.business.insight.core")
@IncludeEngines("junit-jupiter")
@IncludeTags("security")
public class SecuritySuite {}
