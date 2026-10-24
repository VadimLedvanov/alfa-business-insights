package alpha.business.insight.core.qa.helpers;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Создаёт точные по размеру PDF-файлы на основе опубликованного отчёта ЦБ. */
public final class SizedPdfFixtures {
    public static final long MIB = 1024L * 1024L;
    public static final long MAX_UPLOAD_BYTES = 50 * MIB;
    private static final String SEED_SHA256 =
            "ad659e26473e98f7eb1994cb35c0b3df13fc4d7732dc8fd61b5eeddd200e0a02";
    private static final long SEED_SIZE = 486_115L;
    private static final String SEED_ROOT = "263 0 R";
    private static final long SEED_XREF = 116L;
    private static final long MAX_OBJECT_SIZE = 302L;

    /** Запрещает создавать экземпляры генератора. */
    private SizedPdfFixtures() {}

    /** Возвращает путь к сохранённому PDF размером 36, 50 или 51 MiB. */
    public static Path savedPdf(int mebibytes) throws IOException {
        if (mebibytes != 36 && mebibytes != 50 && mebibytes != 51) {
            throw new IllegalArgumentException("Only the 36, 50, and 51 MiB fixtures are saved");
        }
        Path output =
                testsDirectory()
                        .resolve(
                                "fixtures/pdf/cbr-inflation-expectations-2024-01-padded-"
                                        + mebibytes
                                        + "mib.pdf");
        long targetBytes = mebibytes * MIB;
        byte[] generated = pdfBytesOfSize(targetBytes);
        String expectedSha256 = expectedFixtureSha256(mebibytes);
        if (!sha256(generated).equals(expectedSha256)) {
            throw new IOException("Generated PDF does not match its recorded SHA-256");
        }
        if (!isValidFixture(output, targetBytes, expectedSha256)) {
            writeAtomically(output, generated);
        }
        return output;
    }

    /** Возвращает исходный отчёт с корректной incremental revision до указанного размера. */
    public static byte[] pdfBytesOfSize(long targetBytes) throws IOException {
        Path seedPath =
                mlDirectory().resolve("tests/fixtures/pdf/cbr-inflation-expectations-2024-01.pdf");
        byte[] seed = Files.readAllBytes(seedPath);
        if (seed.length != SEED_SIZE || !sha256(seed).equals(SEED_SHA256)) {
            throw new IOException(
                    "The original CBR PDF fixture does not match its recorded size and SHA-256");
        }
        if (targetBytes < seed.length || targetBytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "Target size must fit in an in-memory Java byte array and contain the source PDF");
        }

        long xrefOffset = targetBytes - 128;
        byte[] suffix;
        for (int attempt = 0; ; attempt++) {
            suffix = incrementalSuffix(xrefOffset);
            long adjustedOffset = targetBytes - suffix.length;
            if (adjustedOffset == xrefOffset) break;
            if (attempt >= 8 || adjustedOffset <= seed.length) {
                throw new IllegalArgumentException(
                        "Could not fit the incremental PDF trailer to the requested size");
            }
            xrefOffset = adjustedOffset;
        }

        byte[] result = new byte[(int) targetBytes];
        System.arraycopy(seed, 0, result, 0, seed.length);
        int offset = seed.length;
        byte[] paddingLine = "% upload-size fixture padding\n".getBytes(StandardCharsets.US_ASCII);
        while (xrefOffset - offset >= paddingLine.length) {
            System.arraycopy(paddingLine, 0, result, offset, paddingLine.length);
            offset += paddingLine.length;
        }
        while (offset < xrefOffset) result[offset++] = ' ';
        System.arraycopy(suffix, 0, result, offset, suffix.length);
        return result;
    }

    /** Формирует новую xref/trailer revision, ссылающуюся на xref исходного PDF. */
    private static byte[] incrementalSuffix(long xrefOffset) {
        String suffix =
                "xref\n0 1\n0000000000 65535 f \ntrailer\n<< /Size "
                        + MAX_OBJECT_SIZE
                        + " /Root "
                        + SEED_ROOT
                        + " /Prev "
                        + SEED_XREF
                        + " >>\nstartxref\n"
                        + xrefOffset
                        + "\n%%EOF\n";
        return suffix.getBytes(StandardCharsets.US_ASCII);
    }

    /** Возвращает контрольную сумму каждого сохраняемого fixture. */
    private static String expectedFixtureSha256(int mebibytes) {
        return switch (mebibytes) {
            case 36 -> "2cb36ec29380a5349e011eb02e3cf661a76ebb8d8b1d892d5550b663b87a963b";
            case 50 -> "0fee22ae19b03b02deee3bc123b179042812bc0a3c57756ec4df8ba222dfd179";
            case 51 -> "ca86fced5a41de38c1132291cd2313cbf19ea9c1d5eaf987bc91a91521b5dd07";
            default ->
                    throw new IllegalArgumentException(
                            "No saved PDF fixture for " + mebibytes + " MiB");
        };
    }

    /** Проверяет размер и полный SHA-256 уже сохранённого файла. */
    private static boolean isValidFixture(Path path, long targetBytes, String expectedSha256)
            throws IOException {
        if (!Files.isRegularFile(path) || Files.size(path) != targetBytes) return false;
        return sha256(path).equals(expectedSha256);
    }

    /** Сохраняет сгенерированные байты через временный файл, чтобы параллельные тесты не читали частичный PDF. */
    private static void writeAtomically(Path path, byte[] content) throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), "sized-pdf-", ".tmp");
        try {
            Files.write(temporary, content);
            try {
                Files.move(
                        temporary,
                        path,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** Вычисляет SHA-256 файла, не загружая весь крупный fixture в дополнительный массив. */
    private static String sha256(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    /** Находит корень Maven-модуля в рабочей копии теста. */
    private static Path testsDirectory() {
        Path directory = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (directory != null) {
            Path candidate = directory.resolve("backend/tests");
            if (Files.isDirectory(candidate)) return candidate;
            directory = directory.getParent();
        }
        throw new IllegalStateException(
                "Cannot find backend/tests under the test working directory");
    }

    /** Находит исходный ML PDF относительно рабочей копии проекта. */
    private static Path mlDirectory() {
        Path directory = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (directory != null) {
            Path candidate = directory.resolve("ml");
            if (Files.isRegularFile(candidate.resolve("Dockerfile"))) return candidate;
            directory = directory.getParent();
        }
        throw new IllegalStateException(
                "Cannot find ml/Dockerfile under the test working directory");
    }

    /** Вычисляет SHA-256 для проверки неизменности оригинального PDF. */
    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
