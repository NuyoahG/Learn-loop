package com.learnloop.material;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 上传入口的输入边界测试，不启动 Spring，也不访问数据库。
 *
 * 文件名检查与内容结构检查分开验证：后缀可用于选择处理器，
 * 但把任意文件改名成 .docx 或 .pdf，不能因此通过基本格式检查。
 */
class MaterialUploadValidatorTest {

    private static final String CONTENT_TYPES = """
            <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
              <Override PartName="/word/document.xml"
                ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
            </Types>
            """;
    private static final String DOCUMENT = """
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body><w:p><w:r><w:t>学习资料正文</w:t></w:r></w:p></w:body>
            </w:document>
            """;

    @TempDir
    Path directory;

    private final MaterialUploadValidator validator = new MaterialUploadValidator();

    @Test
    void chineseDisplayNameAndUpperCaseExtensionAreSupported() {
        var name = validator.validateName("Java学习笔记.DOCX");

        assertEquals("Java学习笔记.DOCX", name.originalName());
        assertEquals(MaterialFileType.DOCX, name.fileType());
    }

    @Test
    void pathFragmentsAndControlCharactersCannotBecomeDisplayNames() {
        for (String name : new String[]{"../notes.txt", "folder/notes.txt",
                "folder\\notes.txt", "C:\\notes.txt", "notes\u0000.txt", "notes\n.txt"}) {
            MaterialException exception = assertThrows(MaterialException.class,
                    () -> validator.validateName(name), name);
            assertEquals(400, exception.getStatus());
            assertFalse(exception.getErrorCode().isBlank());
        }
    }

    @Test
    void legacyWordMacrosAndDoubleExtensionExecutableAreUnsupported() {
        for (String name : new String[]{"notes.doc", "notes.docm", "notes.txt.exe"}) {
            MaterialException exception = assertThrows(MaterialException.class,
                    () -> validator.validateName(name), name);
            assertEquals(415, exception.getStatus());
        }
    }

    @Test
    void ordinaryZipRenamedToDocxIsRejected() throws IOException {
        Path archive = directory.resolve("renamed.docx");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("notes.txt"));
            zip.write("This is a ZIP, not a Word document.".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        assertThrows(MaterialException.class,
                () -> validator.validateStructure(archive, MaterialFileType.DOCX));
    }

    @Test
    void plainTextRenamedToPdfIsRejected() throws IOException {
        Path document = directory.resolve("renamed.pdf");
        Files.writeString(document, "This file has no PDF header or document structure.");

        assertThrows(MaterialException.class,
                () -> validator.validateStructure(document, MaterialFileType.PDF));
    }

    @Test
    void basicWordPackageCanPassWithoutExtractingOrRewritingItsText() throws IOException {
        Path document = directory.resolve("valid.docx");
        writeDocx(document, DOCUMENT, null, 0);

        validator.validateStructure(document, MaterialFileType.DOCX);
    }

    @Test
    void docxWithTraversalEntryIsRejectedWithoutWritingOutsidePackage() throws IOException {
        Path document = directory.resolve("traversal.docx");
        writeDocx(document, DOCUMENT, "../outside.txt", 0);

        assertThrows(MaterialException.class,
                () -> validator.validateStructure(document, MaterialFileType.DOCX));
        org.junit.jupiter.api.Assertions.assertFalse(Files.exists(directory.resolve("outside.txt")));
    }

    @Test
    void wordXmlCannotUseDoctypeOrExternalEntities() throws IOException {
        Path document = directory.resolve("external-entity.docx");
        String unsafeXml = """
                <!DOCTYPE w:document [<!ENTITY local SYSTEM "file:///definitely-not-a-real-learnloop-file">]>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body><w:p><w:r><w:t>&local;</w:t></w:r></w:p></w:body>
                </w:document>
                """;
        writeDocx(document, unsafeXml, null, 0);

        assertThrows(MaterialException.class,
                () -> validator.validateStructure(document, MaterialFileType.DOCX));
    }

    @Test
    void docxWithTooManyEntriesFailsEvenWhenCompressedFileIsSmall() throws IOException {
        Path document = directory.resolve("many-entries.docx");
        // 2 个必要 XML 加 999 个附加项，超过契约中的 1,000 项上限。
        writeDocx(document, DOCUMENT, null, 999);

        MaterialException exception = assertThrows(MaterialException.class,
                () -> validator.validateStructure(document, MaterialFileType.DOCX));

        assertEquals(413, exception.getStatus());
    }

    @Test
    void utf8DecodingRemainsTheLaterParsersResponsibility() throws IOException {
        // 3.1A 只负责可靠保管。此处不能以平台默认编码偷偷修正用户的原文件。
        // 下一里程碑严格解码时，此样例应成为可解释的解析失败。
        Path document = directory.resolve("legacy-encoding.txt");
        byte[] original = new byte[]{(byte) 0xD6, (byte) 0xD0, (byte) 0xCE, (byte) 0xC4};
        Files.write(document, original);

        validator.validateStructure(document, MaterialFileType.TXT);

        org.junit.jupiter.api.Assertions.assertArrayEquals(original, Files.readAllBytes(document));
    }

    private void writeDocx(Path target, String document, String extraEntry, int extraCount) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(target))) {
            writeEntry(zip, "[Content_Types].xml", CONTENT_TYPES);
            writeEntry(zip, "word/document.xml", document);
            if (extraEntry != null) writeEntry(zip, extraEntry, "should not be unpacked");
            for (int i = 0; i < extraCount; i++) writeEntry(zip, "extra/" + i + ".txt", "x");
        }
    }

    private void writeEntry(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
