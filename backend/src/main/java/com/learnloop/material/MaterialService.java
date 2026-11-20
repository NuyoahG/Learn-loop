package com.learnloop.material;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 负责上传业务顺序：校验身份/请求键 → 有界落临时文件并 hash → 查重 →
 * 保管正式原件 → 短事务插入 → 提交成功才返回 201。
 * 文件系统不是 MySQL 事务的一部分，因此每一条失败路径都必须明确谁清理自己的文件。
 */
@Service
public class MaterialService {
    private static final Logger log = LoggerFactory.getLogger(MaterialService.class);
    private final MaterialMapper mapper;
    private final MaterialFileStore store;
    private final MaterialUploadValidator validator;
    private final TransactionTemplate transaction;
    private final JsonMapper json = JsonMapper.builder().build();
    public record UploadResult(MaterialResponse material, boolean created) {}

    public MaterialService(MaterialMapper mapper, MaterialFileStore store,
            MaterialUploadValidator validator, PlatformTransactionManager manager) {
        this.mapper = mapper; this.store = store; this.validator = validator;
        this.transaction = new TransactionTemplate(manager);
    }

    public UploadResult upload(Long userId, MultipartFile file, String requestId) {
        requireUser(userId);
        String key = requestKey(requestId);
        if (file == null) throw new MaterialException(400, "FILE_REQUIRED", "请选择一份文件。");
        var name = validator.validateName(file.getOriginalFilename());
        try (var staged = store.stage(file, name.fileType())) {
            MaterialRow existing = mapper.findByRequestId(userId, key);
            if (existing != null) return replay(existing, staged);

            store.promote(staged);
            MaterialRow row = new MaterialRow();
            row.setUserId(userId); row.setOriginalName(name.originalName());
            row.setFileType(name.fileType().extension()); row.setSizeBytes(staged.sizeBytes());
            row.setSha256(staged.sha256()); row.setStorageKey(staged.storageKey()); row.setRequestId(key);
            row.setParseStatus("UPLOADED"); row.setParseAttempt(0L);
            row.setWarnings("[]"); row.setBlockCount(0); row.setTextCharCount(0);
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            row.setCreatedAt(now); row.setUpdatedAt(now);
            // UNKNOWN 表示提交回执不明确；这时宁可保留单个待核查副本，也不能删掉已提交资料的原件。
            AtomicInteger completion = new AtomicInteger(TransactionSynchronization.STATUS_UNKNOWN);
            try {
                transaction.executeWithoutResult(status -> {
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override public void afterCompletion(int result) { completion.set(result); }
                    });
                    if (mapper.insert(row) != 1 || row.getId() == null) {
                        throw new IllegalStateException("Material insert did not return a generated ID");
                    }
                });
                staged.retain();
                return new UploadResult(response(row), true);
            } catch (DuplicateKeyException ex) {
                // 此时原事务已经回滚，在新查询中读取并发赢家，不能在旧快照内查。
                MaterialRow winner = mapper.findByRequestId(userId, key);
                if (winner == null) throw ex; // 若是存储键等其他约束冲突，不能假称请求重放成功。
                return replay(winner, staged);
            } catch (RuntimeException ex) {
                if (completion.get() != TransactionSynchronization.STATUS_ROLLED_BACK) {
                    staged.retain();
                    log.error("Material transaction outcome uncertain; retained key={} for precise reconciliation",
                            staged.storageKey());
                }
                throw ex;
            }
        }
    }

    private UploadResult replay(MaterialRow existing, MaterialFileStore.StagedFile file) {
        // 文件名不是请求指纹：重传改了展示名也保留首次名称，不能借重放实现改名。
        if (!existing.getFileType().equals(file.fileType().extension())
                || existing.getSizeBytes() != file.sizeBytes()
                || !existing.getSha256().equals(file.sha256())) {
            throw new MaterialException(409, "UPLOAD_REQUEST_CONFLICT", "该上传请求已用于其他内容，请为新文件创建新的请求。");
        }
        return new UploadResult(response(existing), false);
    }

    public MaterialResponse detail(Long userId, Long id) {
        return response(ownedMaterial(userId, id));
    }

    public record Original(java.nio.file.Path path, String name, String type) {}

    public Original original(Long userId, Long id) {
        MaterialRow row = ownedMaterial(userId, id);
        return new Original(store.verifiedOriginal(row), row.getOriginalName(), row.getFileType());
    }

    private MaterialRow ownedMaterial(Long userId, Long id) {
        requireUser(userId);
        if (id == null || id <= 0) throw new MaterialException(400, "INVALID_PARAMETER", "资料编号必须是正整数。");
        MaterialRow row = mapper.findById(userId, id);
        if (row == null) throw new MaterialException(404, "MATERIAL_NOT_FOUND", "没有找到可访问的资料。");
        return row;
    }

    public MaterialPageResponse page(Long userId, int page, int size) {
        requireUser(userId);
        if (page < 1 || size < 1 || size > 50) {
            throw new MaterialException(400, "INVALID_PARAMETER", "page 必须至少为 1，size 必须在 1 到 50 之间。");
        }
        long total = mapper.count(userId);
        long offset = ((long) page - 1) * size;
        List<MaterialResponse> records = mapper.findPage(userId, size, offset).stream().map(this::response).toList();
        return new MaterialPageResponse(page, size, total, total / size + (total % size == 0 ? 0 : 1), records);
    }

    private MaterialResponse response(MaterialRow row) {
        List<MaterialResponse.Warning> warnings = json.readValue(row.getWarnings(),
                new TypeReference<List<MaterialResponse.Warning>>() {});
        return new MaterialResponse(row.getId(), row.getOriginalName(), row.getFileType(), row.getSizeBytes(),
                row.getSha256(), row.getParseStatus(), row.getParseAttempt(), row.getParserVersion(),
                row.getErrorCode(), row.getErrorMessage(), warnings, row.getBlockCount(), row.getTextCharCount(),
                row.getCreatedAt().toInstant(ZoneOffset.UTC), row.getUpdatedAt().toInstant(ZoneOffset.UTC),
                row.getParsedAt() == null ? null : row.getParsedAt().toInstant(ZoneOffset.UTC));
    }
    private String requestKey(String value) {
        if (value == null || !value.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw new MaterialException(400, "INVALID_REQUEST_ID", "requestId 必须是标准 UUID。");
        }
        return UUID.fromString(value).toString().toLowerCase(Locale.ROOT);
    }
    private void requireUser(Long userId) {
        if (userId == null || userId <= 0) throw new MaterialException(401, "LOGIN_REQUIRED", "请先登录。");
    }
}
