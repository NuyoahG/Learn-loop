package com.learnloop.material;

/** 单位是字节。文本为 2 MiB，二进制文档为 100 MiB，与前端提示分开独立校验。 */
public enum MaterialFileType {
    TXT("txt", 2L * 1024 * 1024),
    MD("md", 2L * 1024 * 1024),
    DOCX("docx", 100L * 1024 * 1024),
    PDF("pdf", 100L * 1024 * 1024);

    private final String extension;
    private final long maxBytes;
    MaterialFileType(String extension, long maxBytes) {
        this.extension = extension;
        this.maxBytes = maxBytes;
    }
    public String extension() { return extension; }
    public long maxBytes() { return maxBytes; }
}
