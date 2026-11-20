package com.learnloop.material;

import com.learnloop.config.MaterialProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 使用 JUnit 的独立临时目录验证真实文件流，不接触项目的实际资料目录。
 * 重点检查声明长度不可信、读流中断，以及临时/正式文件的清理责任。
 */
class MaterialFileStoreTest {

    @TempDir
    Path directory;
    private Path storageRoot;
    private MaterialFileStore store;

    @BeforeEach
    void setUp() {
        storageRoot = directory.resolve("materials");
        MaterialProperties properties = new MaterialProperties();
        properties.setStorageRoot(storageRoot);
        store = new MaterialFileStore(properties, new MaterialUploadValidator());
    }

    @Test
    void actualStreamOverLimitIsRejectedEvenWhenReportedSizeIsSmall() throws Exception {
        byte[] content = new byte[(int) MaterialFileType.TXT.maxBytes() + 1];
        AtomicBoolean inputClosed = new AtomicBoolean();
        MultipartFile file = streamFile(content, 1, inputClosed);

        MaterialException exception = assertThrows(MaterialException.class,
                () -> store.stage(file, MaterialFileType.TXT));

        assertEquals(413, exception.getStatus());
        assertEquals("FILE_TOO_LARGE", exception.getErrorCode());
        assertTrue(inputClosed.get(), "拒绝超大流后仍须关闭输入流");
        assertEquals(0, storedFileCount(), "失败请求不得留下半份原件");
    }

    @Test
    void actualEmptyStreamIsRejectedEvenWhenReportedSizeIsPositive() throws Exception {
        AtomicBoolean inputClosed = new AtomicBoolean();

        MaterialException exception = assertThrows(MaterialException.class,
                () -> store.stage(streamFile(new byte[0], 100, inputClosed), MaterialFileType.TXT));

        assertEquals(400, exception.getStatus());
        assertEquals("EMPTY_FILE", exception.getErrorCode());
        assertTrue(inputClosed.get());
        assertEquals(0, storedFileCount());
    }

    @Test
    void exactTextSizeLimitCanBePreservedWithoutChangingBytes() throws Exception {
        byte[] content = new byte[(int) MaterialFileType.TXT.maxBytes()];
        content[0] = 'A';
        content[content.length - 1] = 'Z';
        String storageKey;

        try (var staged = store.stage(new MockMultipartFile("file", "limit.txt",
                "text/plain", content), MaterialFileType.TXT)) {
            assertEquals(content.length, staged.sizeBytes());
            assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)),
                    staged.sha256());
            store.promote(staged);
            storageKey = staged.storageKey();
            staged.retain();
        }

        assertEquals(1, storedFileCount(), "提交后仅保留一份正式原件，临时文件应已清理");
        assertArrayEquals(content, Files.readAllBytes(storageRoot.resolve(storageKey)));
    }

    @Test
    void exactPdfSizeLimitSurvivesUploadAndOriginalVerification() throws Exception {
        long limit = 100L * 1024 * 1024;
        Path source = directory.resolve("limit.pdf");
        // 这是存储层封装样例，后续 Python 测试使用可解析的 PDF。
        try (var file = new RandomAccessFile(source.toFile(), "rw")) {
            file.writeBytes("%PDF-1.7\n");
            String trailer = "\nstartxref\n0\n%%EOF\n";
            file.seek(limit - trailer.length());
            file.writeBytes(trailer);
        }
        MultipartFile upload = mock(MultipartFile.class);
        when(upload.getSize()).thenReturn(limit);
        when(upload.getInputStream()).thenAnswer(invocation -> Files.newInputStream(source));
        try (var staged = store.stage(upload, MaterialFileType.PDF)) {
            store.promote(staged);
            var row = new MaterialRow();
            row.setFileType("pdf");
            row.setStorageKey(staged.storageKey());
            row.setSizeBytes(staged.sizeBytes());
            row.setSha256(staged.sha256());
            assertEquals(storageRoot.resolve(staged.storageKey()), store.verifiedOriginal(row));
        }
        when(upload.getSize()).thenReturn(limit + 1);
        var tooLarge = assertThrows(MaterialException.class, () -> store.stage(upload, MaterialFileType.PDF));
        assertEquals("FILE_TOO_LARGE", tooLarge.getErrorCode());
    }

    @Test
    void promotedButUncommittedRequestCleansOnlyItsOwnCopies() throws Exception {
        byte[] winner = "Previously committed material".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Path previous = storageRoot.resolve("previous.bin");
        Files.write(previous, winner);

        try (var staged = store.stage(new MockMultipartFile("file", "new.txt", "text/plain",
                new byte[]{1, 2, 3}), MaterialFileType.TXT)) {
            store.promote(staged);
            assertTrue(Files.exists(storageRoot.resolve(staged.storageKey())));
            // 不调用 retain：模拟数据库回滚后的请求退出。
        }

        assertEquals(1, storedFileCount());
        assertArrayEquals(winner, Files.readAllBytes(previous), "清理必须保留其他请求的既有原件");
    }

    @Test
    void interruptedInputClosesStreamAndRemovesPartialTemporaryFile() throws Exception {
        AtomicBoolean closed = new AtomicBoolean();
        InputStream interrupted = new InputStream() {
            private boolean firstRead = true;
            @Override public int read() throws IOException { throw new IOException("simulated read failure"); }
            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                if (!firstRead) throw new IOException("simulated read failure");
                firstRead = false;
                bytes[offset] = 7;
                return 1;
            }
            @Override public void close() { closed.set(true); }
        };
        MultipartFile file = mock(MultipartFile.class);
        when(file.getSize()).thenReturn(10L);
        when(file.getInputStream()).thenReturn(interrupted);

        MaterialException exception = assertThrows(MaterialException.class,
                () -> store.stage(file, MaterialFileType.TXT));

        assertEquals(500, exception.getStatus());
        assertEquals("FILE_SAVE_FAILED", exception.getErrorCode());
        assertTrue(closed.get());
        assertEquals(0, storedFileCount());
    }

    private MultipartFile streamFile(byte[] bytes, long reportedSize, AtomicBoolean closed) throws IOException {
        MultipartFile file = mock(MultipartFile.class);
        when(file.getSize()).thenReturn(reportedSize);
        when(file.getInputStream()).thenReturn(new FilterInputStream(new ByteArrayInputStream(bytes)) {
            @Override public void close() throws IOException {
                closed.set(true);
                super.close();
            }
        });
        return file;
    }

    private long storedFileCount() throws IOException {
        try (var files = Files.walk(storageRoot)) {
            return files.filter(Files::isRegularFile).count();
        }
    }
}
