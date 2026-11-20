package com.learnloop.material;

import com.learnloop.config.MaterialProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 文件系统不属于数据库事务，因此测试“业务结果与磁盘事实”必须同时断言。
 * 使用 Spring 真实事务同步流程的测试管理器，只替代数据库提交/回滚动作；
 * 没有 MySQL 连接，也不以手动调用 afterCompletion 来假冒事务已完成。
 */
class MaterialServiceCompensationTest {

    private static final Long USER_ID = 42L;
    private static final String REQUEST_ID = "c791d39f-1d63-41fd-b566-10cccf03da05";
    private static final byte[] CONTENT = "A committed learning note.".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path directory;
    private Path storageRoot;
    private MaterialMapper mapper;
    private TestTransactionManager manager;
    private MaterialService service;

    @BeforeEach
    void setUp() {
        storageRoot = directory.resolve("materials");
        MaterialProperties properties = new MaterialProperties();
        properties.setStorageRoot(storageRoot);
        MaterialUploadValidator validator = new MaterialUploadValidator();
        MaterialFileStore store = new MaterialFileStore(properties, validator);
        mapper = mock(MaterialMapper.class);
        manager = new TestTransactionManager();
        service = new MaterialService(mapper, store, validator, manager);
    }

    @Test
    void successfulCommitKeepsOneOriginalAndRemovesTemporaryCopy() throws Exception {
        when(mapper.insert(any(MaterialRow.class))).thenAnswer(invocation -> {
            invocation.<MaterialRow>getArgument(0).setId(101L);
            return 1;
        });

        var result = service.upload(USER_ID, file(CONTENT), REQUEST_ID);

        assertTrue(result.created());
        assertEquals(101L, result.material().materialId());
        assertEquals(1, manager.commits);
        assertEquals(0, manager.rollbacks);
        assertEquals(1, storedFiles().size());
        assertArrayEquals(CONTENT, Files.readAllBytes(storedFiles().getFirst()));
    }

    @Test
    void confirmedRollbackRemovesTemporaryAndFormalCopies() throws Exception {
        when(mapper.insert(any(MaterialRow.class)))
                .thenThrow(new DataIntegrityViolationException("simulated rejected insert"));

        assertThrows(DataIntegrityViolationException.class,
                () -> service.upload(USER_ID, file(CONTENT), REQUEST_ID));

        assertEquals(1, manager.rollbacks);
        assertEquals(0, manager.commits);
        assertEquals(0, storedFiles().size(), "已确认回滚后不得留下本次正式文件或临时文件");
    }

    @Test
    void unknownCommitOutcomeRetainsOriginalForReconciliation() throws Exception {
        manager.failCommitReceipt = true;
        when(mapper.insert(any(MaterialRow.class))).thenAnswer(invocation -> {
            invocation.<MaterialRow>getArgument(0).setId(101L);
            return 1;
        });

        assertThrows(TransactionSystemException.class,
                () -> service.upload(USER_ID, file(CONTENT), REQUEST_ID));

        assertEquals(1, manager.commits);
        assertEquals(0, manager.rollbacks, "丢失提交回执不等于已经回滚");
        assertEquals(1, storedFiles().size(), "可能已经提交的记录，其原件必须保留等待核查");
        assertArrayEquals(CONTENT, Files.readAllBytes(storedFiles().getFirst()));
        assertFalse(storedFiles().getFirst().startsWith(storageRoot.resolve(".tmp")),
                "保留的是正式原件，不是临时上传副本");
    }

    @Test
    void duplicateInsertReturnsWinnerAndDeletesOnlyLosingRequestsFiles() throws Exception {
        MaterialRow winner = committedRow(CONTENT);
        Path winnerFile = storageRoot.resolve(winner.getStorageKey());
        Files.write(winnerFile, CONTENT);
        when(mapper.findByRequestId(USER_ID, REQUEST_ID)).thenReturn(null, winner);
        when(mapper.insert(any(MaterialRow.class)))
                .thenThrow(new DuplicateKeyException("simulated concurrent winner"));

        var result = service.upload(USER_ID, file(CONTENT), REQUEST_ID);

        assertFalse(result.created());
        assertEquals(winner.getId(), result.material().materialId());
        assertEquals(1, manager.rollbacks);
        assertEquals(List.of(winnerFile), storedFiles(), "竞争失败方只能清理自身副本，不能删除赢家文件");
        assertArrayEquals(CONTENT, Files.readAllBytes(winnerFile));
        verify(mapper, times(2)).findByRequestId(USER_ID, REQUEST_ID);
    }

    @Test
    void duplicateRequestWithDifferentBytesConflictsAndPreservesWinner() throws Exception {
        MaterialRow winner = committedRow(CONTENT);
        Path winnerFile = storageRoot.resolve(winner.getStorageKey());
        Files.write(winnerFile, CONTENT);
        when(mapper.findByRequestId(USER_ID, REQUEST_ID)).thenReturn(null, winner);
        when(mapper.insert(any(MaterialRow.class)))
                .thenThrow(new DuplicateKeyException("simulated concurrent winner"));

        MaterialException exception = assertThrows(MaterialException.class,
                () -> service.upload(USER_ID, file("Different note".getBytes(StandardCharsets.UTF_8)), REQUEST_ID));

        assertEquals(409, exception.getStatus());
        assertEquals("UPLOAD_REQUEST_CONFLICT", exception.getErrorCode());
        assertEquals(List.of(winnerFile), storedFiles());
        assertArrayEquals(CONTENT, Files.readAllBytes(winnerFile));
    }

    @Test
    void replayBeforeInsertDoesNotCreateAnotherFormalOriginal() throws Exception {
        MaterialRow winner = committedRow(CONTENT);
        Path winnerFile = storageRoot.resolve(winner.getStorageKey());
        Files.write(winnerFile, CONTENT);
        when(mapper.findByRequestId(USER_ID, REQUEST_ID)).thenReturn(winner);

        var result = service.upload(USER_ID, file(CONTENT), REQUEST_ID);

        assertFalse(result.created());
        assertEquals(List.of(winnerFile), storedFiles());
        verify(mapper, never()).insert(any());
        assertEquals(0, manager.commits);
        assertEquals(0, manager.rollbacks);
    }

    private MockMultipartFile file(byte[] content) {
        return new MockMultipartFile("file", "notes.txt", "text/plain", content);
    }

    @Test
    void originalRequiresOwnershipAndReturnsTheUnparsedFile() throws Exception {
        MaterialRow row = committedRow(CONTENT);
        row.setStorageKey("12345678-1234-4234-9234-123456789abc.bin");
        Path original = storageRoot.resolve(row.getStorageKey());
        Files.write(original, CONTENT);
        when(mapper.findById(USER_ID, row.getId())).thenReturn(row);

        MaterialException denied = assertThrows(MaterialException.class,
                () -> service.original(USER_ID + 1, row.getId()));
        assertEquals(404, denied.getStatus());
        assertArrayEquals(CONTENT, Files.readAllBytes(service.original(USER_ID, row.getId()).path()));

        // 原件被改写后不能向浏览器返回未经验证的内容。
        byte[] changed = CONTENT.clone();
        changed[0] ^= 1;
        Files.write(original, changed);
        assertThrows(MaterialException.class, () -> service.original(USER_ID, row.getId()));
    }

    private MaterialRow committedRow(byte[] content) throws Exception {
        MaterialRow row = new MaterialRow();
        row.setId(100L); row.setUserId(USER_ID); row.setOriginalName("first-name.txt");
        row.setFileType("txt"); row.setSizeBytes((long) content.length);
        row.setSha256(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)));
        row.setStorageKey("winner.bin"); row.setRequestId(REQUEST_ID);
        row.setParseStatus("UPLOADED"); row.setParseAttempt(0L); row.setWarnings("[]");
        row.setBlockCount(0); row.setTextCharCount(0);
        row.setCreatedAt(LocalDateTime.of(2026, 10, 22, 1, 0));
        row.setUpdatedAt(row.getCreatedAt());
        return row;
    }

    private List<Path> storedFiles() throws IOException {
        try (var files = Files.walk(storageRoot)) {
            return files.filter(Files::isRegularFile).sorted().toList();
        }
    }

    private static final class TestTransactionManager extends AbstractPlatformTransactionManager {
        private int commits;
        private int rollbacks;
        private boolean failCommitReceipt;

        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
        @Override protected void doCommit(DefaultTransactionStatus status) {
            commits++;
            if (failCommitReceipt) throw new TransactionSystemException("simulated lost commit receipt");
        }
        @Override protected void doRollback(DefaultTransactionStatus status) { rollbacks++; }
    }
}
