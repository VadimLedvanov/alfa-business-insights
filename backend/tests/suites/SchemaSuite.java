package alpha.business.insight.core.qa.suites;

import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.IncludeTags;
import org.junit.platform.suite.api.SelectPackages;
import org.junit.platform.suite.api.Suite;
import org.junit.platform.suite.api.SuiteDisplayName;

/** Запускает тесты схемы и миграций базы данных. */
@Suite
@SuiteDisplayName("Тесты схемы базы данных")
@SelectPackages("alpha.business.insight.core")
@IncludeEngines("junit-jupiter")
@IncludeTags("schema")
public class SchemaSuite {
}
