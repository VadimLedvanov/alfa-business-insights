package alpha.business.insight.core.qa.helpers;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Создаёт компактные синтетические PDF и multipart-запросы для изолированных тестов. */
public final class SyntheticPdf {
    /** Хранит PDF и поля multipart-загрузки для формирования запроса клиентом. */
    public record MultipartUpload(
            String filename,
            String contentType,
            byte[] pdf,
            String publicationDate,
            String description) {}

    /** Запрещает создавать экземпляры класса-генератора. */
    private SyntheticPdf() {}

    /** Возвращает небольшой PDF с корректными объектами, таблицей ссылок и трейлером. */
    public static byte[] validDocument() {
        try {
            ByteArrayOutputStream pdf = new ByteArrayOutputStream();
            pdf.write(
                    "%PDF-1.4\n%\u00e2\u00e3\u00cf\u00d3\n".getBytes(StandardCharsets.ISO_8859_1));
            List<Integer> offsets = new ArrayList<>();
            List<String> objects =
                    List.of(
                            "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n",
                            "2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n",
                            "3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 144] /Contents 4 0 R >>\nendobj\n",
                            "4 0 obj\n<< /Length 4 >>\nstream\nq Q\nendstream\nendobj\n");
            for (String object : objects) {
                offsets.add(pdf.size());
                pdf.write(object.getBytes(StandardCharsets.US_ASCII));
            }
            int xrefOffset = pdf.size();
            StringBuilder xref = new StringBuilder("xref\n0 5\n0000000000 65535 f \n");
            for (int offset : offsets)
                xref.append(String.format(Locale.ROOT, "%010d 00000 n \n", offset));
            xref.append("trailer\n<< /Size 5 /Root 1 0 R >>\nstartxref\n")
                    .append(xrefOffset)
                    .append("\n%%EOF\n");
            pdf.write(xref.toString().getBytes(StandardCharsets.US_ASCII));
            return pdf.toByteArray();
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }
    }

    /** Возвращает PDF с отдельной физической страницей и заданным текстом на каждой странице. */
    public static byte[] textDocument(List<String> pages) {
        if (pages == null
                || pages.stream()
                        .anyMatch(
                                page ->
                                        page == null
                                                || page.chars()
                                                        .anyMatch(
                                                                character ->
                                                                        character < 32
                                                                                || character
                                                                                        > 126))) {
            throw new IllegalArgumentException(
                    "PDF fixture supports printable ASCII page text only");
        }
        try {
            ByteArrayOutputStream pdf = new ByteArrayOutputStream();
            pdf.write("%PDF-1.4\n".getBytes(StandardCharsets.US_ASCII));
            List<String> objects = new ArrayList<>();
            objects.add("<< /Type /Catalog /Pages 2 0 R >>");
            int fontNumber = 3 + 2 * pages.size();
            StringBuilder pageReferences = new StringBuilder();
            for (int index = 0; index < pages.size(); index++) {
                pageReferences.append(3 + 2 * index).append(" 0 R ");
            }
            objects.add(
                    "<< /Type /Pages /Kids ["
                            + pageReferences
                            + "] /Count "
                            + pages.size()
                            + " >>");
            for (int index = 0; index < pages.size(); index++) {
                int pageNumber = 3 + 2 * index;
                int contentNumber = pageNumber + 1;
                String escaped =
                        pages.get(index)
                                .replace("\\", "\\\\")
                                .replace("(", "\\(")
                                .replace(")", "\\)");
                byte[] stream =
                        (pages.get(index).isEmpty()
                                        ? ""
                                        : "BT /F1 12 Tf 72 720 Td (" + escaped + ") Tj ET\n")
                                .getBytes(StandardCharsets.US_ASCII);
                objects.add(
                        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] "
                                + "/Resources << /Font << /F1 "
                                + fontNumber
                                + " 0 R >> >> /Contents "
                                + contentNumber
                                + " 0 R >>");
                objects.add(
                        "<< /Length "
                                + stream.length
                                + " >>\nstream\n"
                                + new String(stream, StandardCharsets.US_ASCII)
                                + "endstream");
            }
            objects.add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>");

            List<Integer> offsets = new ArrayList<>();
            for (int index = 0; index < objects.size(); index++) {
                offsets.add(pdf.size());
                pdf.write(
                        (index + 1 + " 0 obj\n" + objects.get(index) + "\nendobj\n")
                                .getBytes(StandardCharsets.US_ASCII));
            }
            int xrefOffset = pdf.size();
            StringBuilder xref =
                    new StringBuilder("xref\n0 ")
                            .append(objects.size() + 1)
                            .append("\n0000000000 65535 f \n");
            for (int offset : offsets)
                xref.append(String.format(Locale.ROOT, "%010d 00000 n \n", offset));
            xref.append("trailer\n<< /Size ")
                    .append(objects.size() + 1)
                    .append(" /Root 1 0 R >>\nstartxref\n")
                    .append(xrefOffset)
                    .append("\n%%EOF\n");
            pdf.write(xref.toString().getBytes(StandardCharsets.US_ASCII));
            return pdf.toByteArray();
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }
    }

    /** Возвращает PDF и значения полей для multipart-загрузки. */
    public static MultipartUpload multipartUpload() {
        String filename = "e2e-report.pdf";
        String contentType = "application/pdf";
        String publicationDate = "2026-10-21";
        byte[] pdf = validDocument();
        String description =
                "multipart fields: filename="
                        + filename
                        + ", content-type="
                        + contentType
                        + ", "
                        + "file="
                        + pdf.length
                        + " bytes, publicationDate="
                        + publicationDate;
        return new MultipartUpload(filename, contentType, pdf, publicationDate, description);
    }
}
