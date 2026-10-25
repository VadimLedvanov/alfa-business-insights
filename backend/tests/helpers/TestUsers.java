package alpha.business.insight.core.qa.helpers;

/** Стабильные учётные записи пользователей, добавляемые только в тестовый realm. */
public final class TestUsers {
    /** Утилиту используют через стабильные тестовые учётные записи. */
    private TestUsers() {}

    /** Роль тестового пользователя и учётные данные для получения настоящего JWT. */
    public enum Actor {
        SYSTEM_ADMIN("sysadmin", "sysadmin-dev", false),
        DIRECTOR("director", "director-dev", false),
        CREDIT_DIRECTOR("director_credit", "credit-dev", true),
        TRADE_DIRECTOR("director_trade", "trade-dev", true),
        UNASSIGNED_DIRECTOR("director_unassigned", "unassigned-dev", true);

        private final String username;
        private final String password;
        private final boolean importedForTests;

        /** Связывает имя пользователя, пароль и признак добавления в тестовый realm. */
        Actor(String username, String password, boolean importedForTests) {
            this.username = username;
            this.password = password;
            this.importedForTests = importedForTests;
        }

        /** Возвращает имя пользователя для получения токена. */
        public String username() {
            return username;
        }

        /** Возвращает пароль тестовой учётной записи. */
        public String password() {
            return password;
        }

        /** Показывает, нужно ли добавить учётную запись в импортируемый realm. */
        public boolean importedForTests() {
            return importedForTests;
        }
    }
}
