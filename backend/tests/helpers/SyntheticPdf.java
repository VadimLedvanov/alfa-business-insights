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
    public record MultipartUpload(String filename, String contentType, byte[] pdf,
                                  String publicationDate, String description) {
    }

    /** Запрещает создавать экземпляры класса-генератора. */
    private SyntheticPdf() {
    }

    /** Возвращает небольшой PDF с корректными объектами, таблицей ссылок и трейлером. */
    public static byte[] validDocument() {
        try {
            ByteArrayOutputStream pdf = new ByteArrayOutputStream();
            pdf.write("%PDF-1.4\n%\u00e2\u00e3\u00cf\u00d3\n".getBytes(StandardCharsets.ISO_8859_1));
            List<Integer> offsets = new ArrayList<>();
            List<String> objects = List.of(
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
            for (int offset : offsets) xref.append(String.format(Locale.ROOT, "%010d 00000 n \n", offset));
            xref.append("trailer\n<< /Size 5 /Root 1 0 R >>\nstartxref\n").append(xrefOffset).append("\n%%EOF\n");
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
        String description = "multipart fields: filename=" + filename + ", content-type=" + contentType + ", "
                + "file=" + pdf.length + " bytes, publicationDate=" + publicationDate;
        return new MultipartUpload(filename, contentType, pdf, publicationDate, description);
    }
}
