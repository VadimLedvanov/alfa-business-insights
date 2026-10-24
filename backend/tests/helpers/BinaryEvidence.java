package alpha.business.insight.core.qa.helpers;

import io.qameta.allure.Allure;
import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Прикладывает бинарное тело либо его точный размер и хеш для больших файлов. */
public final class BinaryEvidence {
    private static final int FULL_ATTACHMENT_LIMIT = 2 * 1024 * 1024;

    /** Запрещает создавать экземпляры утилиты. */
    private BinaryEvidence() {}

    /** Сохраняет большие тела без дублирования десятков мегабайт в каждом отчёте. */
    public static void attach(String name, String contentType, byte[] bytes, String extension) {
        String details = bytes.length + " bytes; SHA-256=" + sha256(bytes);
        if (bytes.length > FULL_ATTACHMENT_LIMIT) {
            Allure.addAttachment(
                    name + " — размер и контрольная сумма",
                    "text/plain",
                    "Content-Type: "
                            + contentType
                            + "\n"
                            + details
                            + "\nБольшое бинарное тело не дублируется в отчёте."
                            + " Проверки используют полные переданные и полученные байты.");
            return;
        }
        Allure.addAttachment(
                name + " (" + details + ")",
                contentType,
                new ByteArrayInputStream(bytes),
                extension);
    }

    /** Вычисляет хеш фактического тела запроса или ответа. */
    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
