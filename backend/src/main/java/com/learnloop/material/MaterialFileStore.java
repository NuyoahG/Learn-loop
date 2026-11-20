package com.learnloop.material;

import com.learnloop.config.MaterialProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

/**
 * 只保管原件，不查询用户、不写数据库。临时文件和正式文件都由服务端随机命名。
 * 使用 AutoCloseable 明确本次请求的清理责任；没有提交成功就不保留正式副本。
 */
@Component
public class MaterialFileStore {
    private static final Logger log = LoggerFactory.getLogger(MaterialFileStore.class);
    private final Path root;
    private final Path temporaryRoot;
    private final MaterialUploadValidator validator;

    public MaterialFileStore(MaterialProperties properties, MaterialUploadValidator validator) {
        if (properties.getStorageRoot() == null || !properties.getStorageRoot().isAbsolute()) {
            throw new IllegalStateException("learnloop.material.storage-root must be an absolute directory");
        }
        this.root = properties.getStorageRoot().normalize();
        this.temporaryRoot = root.resolve(".tmp");
        this.validator = validator;
        try {
            Files.createDirectories(temporaryRoot);
            if (Files.isSymbolicLink(root) || Files.isSymbolicLink(temporaryRoot)
                    || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("Material storage must be an ordinary directory");
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot prepare material storage directory");
        }
    }

    /** 只接受数据库内服务端生成的存储键；原件缺失/变化时失败，绝不解析任意路径。 */
    public Path verifiedOriginal(MaterialRow row) {
        String key = row.getStorageKey();
        if (key == null || !key.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.bin")) {
            throw new MaterialException(500,"ORIGINAL_UNAVAILABLE","资料原件不可用，请检查存储。");
        }
        Path file = root.resolve(key).normalize();
        try {
            // 重读原件时沿用上传的分格式上限，避免已保存的大文件在解析入口被误拒。
            long limit = MaterialFileType.valueOf(String.valueOf(row.getFileType()).toUpperCase(Locale.ROOT)).maxBytes();
            if (!file.getParent().equals(root) || !Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)) throw new IOException();
            long size = Files.size(file);
            if (size != row.getSizeBytes() || size > limit) throw new IOException();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input=Files.newInputStream(file)) {
                byte[] buffer=new byte[8192]; int length; long count=0;
                while((length=input.read(buffer))!=-1) {
                    count+=length; if(count>limit) throw new IOException();
                    digest.update(buffer,0,length);
                }
            }
            if(!HexFormat.of().formatHex(digest.digest()).equals(row.getSha256())) throw new IOException();
            return file;
        } catch(IOException | NoSuchAlgorithmException | IllegalArgumentException ex) {
            throw new MaterialException(500,"ORIGINAL_UNAVAILABLE","资料原件缺失或已变化，请检查存储。");
        }
    }

    public StagedFile stage(MultipartFile file, MaterialFileType type) {
        if (file == null) throw new MaterialException(400, "FILE_REQUIRED", "请选择一份文件。");
        if (file.getSize() > type.maxBytes()) throw tooLarge();
        Path temporary = temporaryRoot.resolve(UUID.randomUUID() + ".part");
        StagedFile staged = new StagedFile(temporary, type);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            // 不能使用 getBytes() 一次性载入整个文件，也不能只相信 getSize()。
            try (InputStream input = file.getInputStream();
                 FileChannel output = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW,
                         StandardOpenOption.WRITE)) {
                staged.ownsTemporary = true;
                byte[] buffer = new byte[8192];
                int n;
                while ((n = input.read(buffer)) != -1) {
                    staged.sizeBytes += n;
                    if (staged.sizeBytes > type.maxBytes()) throw tooLarge();
                    digest.update(buffer, 0, n);
                    ByteBuffer bytes = ByteBuffer.wrap(buffer, 0, n);
                    while (bytes.hasRemaining()) output.write(bytes);
                }
                if (staged.sizeBytes == 0) throw new MaterialException(400, "EMPTY_FILE", "不能上传空文件。");
                output.force(true);
            }
            staged.sha256 = HexFormat.of().formatHex(digest.digest());
            validator.validateStructure(temporary, type);
            return staged;
        } catch (MaterialException ex) {
            staged.close(); throw ex;
        } catch (IOException ex) {
            staged.close();
            throw new MaterialException(500, "FILE_SAVE_FAILED", "暂时无法保存文件，请稍后重试。");
        } catch (NoSuchAlgorithmException ex) {
            staged.close(); throw new IllegalStateException("SHA-256 is unavailable");
        }
    }

    public void promote(StagedFile staged) {
        if (staged.storageKey != null) throw new IllegalStateException("Already promoted");
        String key = UUID.randomUUID() + ".bin";
        Path target = root.resolve(key).normalize();
        if (!target.getParent().equals(root)) throw new IllegalStateException("Invalid server storage key");
        try (InputStream input = Files.newInputStream(staged.temporaryPath);
             FileChannel output = FileChannel.open(target, StandardOpenOption.CREATE_NEW,
                     StandardOpenOption.WRITE)) {
            // 只有 CREATE_NEW 成功后才登记所有权；撞名时绝不能删除已有赢家的文件。
            staged.storageKey = key;
            staged.formalPath = target;
            byte[] buffer = new byte[8192];
            int n;
            while ((n = input.read(buffer)) != -1) {
                ByteBuffer bytes = ByteBuffer.wrap(buffer, 0, n);
                while (bytes.hasRemaining()) output.write(bytes);
            }
            output.force(true);
        } catch (IOException ex) {
            throw new MaterialException(500, "FILE_SAVE_FAILED", "暂时无法保存文件，请稍后重试。");
        }
    }

    private MaterialException tooLarge() {
        return new MaterialException(413, "FILE_TOO_LARGE", "文件超过当前格式的大小上限。");
    }

    public static final class StagedFile implements AutoCloseable {
        private final Path temporaryPath;
        private final MaterialFileType fileType;
        private long sizeBytes;
        private String sha256;
        private String storageKey;
        private Path formalPath;
        private boolean ownsTemporary;
        private boolean retainFormal;
        private StagedFile(Path temporaryPath, MaterialFileType fileType) {
            this.temporaryPath = temporaryPath; this.fileType = fileType;
        }
        public long sizeBytes() { return sizeBytes; }
        public String sha256() { return sha256; }
        public String storageKey() { return storageKey; }
        public MaterialFileType fileType() { return fileType; }
        /** 仅在数据库已提交，或提交结果不明需保留核查时使用。 */
        public void retain() { this.retainFormal = true; }
        @Override public void close() {
            if (ownsTemporary) deleteOwned(temporaryPath);
            if (!retainFormal && formalPath != null) deleteOwned(formalPath);
        }
        private void deleteOwned(Path path) {
            try { Files.deleteIfExists(path); }
            catch (IOException ex) {
                // 不把原文件名和路径写入用户错误；保留单个存储键便于精确核查，不扫目录。
                log.error("Material request cleanup failed; owned key={}", path.getFileName());
            }
        }
    }
}
