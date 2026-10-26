# PDF fixtures

## Synthetic OCR fixture

`ocr-mixed-text-and-scan.pdf` is a synthetic two-page fixture created for the real
backend/Python ML integration test. Page 1 contains an embedded Russian text layer;
page 2 is a raster image of large English text with stable OCR landmarks. It contains
no customer or public-source data. The fixture is 491,123 bytes with SHA-256
`8152d58fa25e248bf08fdcc5e5c8b389fec37d11192bafe40c5b1bda548ba4ad`.
Русская пометка о происхождении: файл полностью синтетический; первая страница
содержит встроенный текст, вторая собрана как изображение страницы со сканированным текстом.

`ocr-password-protected.pdf` is a synthetic AES-256 password-protected one-page PDF
used to verify that the real backend and Python ML service reject protected input
without persisting it. It is 1,801 bytes with SHA-256
`374978b8069597d49a750cdce7592fd887c5321ba5cd7825ee191598cb919c53`.
Русская пометка о происхождении: синтетический PDF, созданный специально для проверки
отказа при наличии пароля; пользовательский пароль тесту не передаётся.

## Upload-size fixtures

Файлы 36, 50 и 51 МиБ сохраняются в этой папке. `SizedPdfFixtures.savedPdf(int)`
восстанавливает отсутствующий файл или файл с неверным SHA-256 из исходного PDF ЦБ
`ml/tests/fixtures/pdf/cbr-inflation-expectations-2024-01.pdf`.

Генератор сохраняет исходные 11 страниц и текст. Размер увеличивается комментариями
PDF и завершается корректным incremental xref/trailer. Это фикстуры точного размера,
а не дополнительные страницы аналитики. SHA-256 исходника:
`ad659e26473e98f7eb1994cb35c0b3df13fc4d7732dc8fd61b5eeddd200e0a02`.

Три больших файла игнорируются Git (137 МиБ суммарно). В проект входят генератор,
компактный исходник и контрольные суммы: на другом компьютере тесты сами создадут
такие же PDF. Для ручной проверки файлы остаются здесь после выполнения тестов.
Варианты 50 МиБ ± 1 байт формируются в памяти только для граничного сценария.

| Файл | Точный размер | SHA-256 |
| --- | ---: | --- |
| `cbr-inflation-expectations-2024-01-padded-36mib.pdf` | 37,748,736 байт | `2cb36ec29380a5349e011eb02e3cf661a76ebb8d8b1d892d5550b663b87a963b` |
| `cbr-inflation-expectations-2024-01-padded-50mib.pdf` | 52,428,800 байт | `0fee22ae19b03b02deee3bc123b179042812bc0a3c57756ec4df8ba222dfd179` |
| `cbr-inflation-expectations-2024-01-padded-51mib.pdf` | 53,477,376 байт | `ca86fced5a41de38c1132291cd2313cbf19ea9c1d5eaf987bc91a91521b5dd07` |
