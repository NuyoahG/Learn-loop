package com.learnloop.material;

import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * 上传时的便宜、有限校验，不替代后续 Python 解析。
 * TXT/MD 的 UTF-8/空白正文检查、PDF 加密/OCR/内容质量检查留到解析阶段；
 * 因此 UPLOADED 只表示原件已保存，绝不能对外表示“已可出题”。
 */
@Component
public class MaterialUploadValidator {
    // DOCX 上传体积和解包体积分别限长。预留图片等媒体占用，同时限制高压缩比文档。
    private static final long ZIP_EXPANDED_LIMIT = 128L * 1024 * 1024;
    private static final int ZIP_ENTRIES_LIMIT = 1000;
    private static final int XML_LIMIT = 4 * 1024 * 1024;

    public record UploadName(String originalName, MaterialFileType fileType) {}

    public UploadName validateName(String name) {
        if (name == null || name.isBlank() || name.codePointCount(0, name.length()) > 255
                || name.endsWith(".") || name.endsWith(" ")
                || name.codePoints().anyMatch(c -> Character.isISOControl(c)
                    || "/\\:<>\"|?*".indexOf(c) >= 0)) {
            throw new MaterialException(400, "INVALID_FILE_NAME", "文件名无效，请使用不含路径或特殊控制字符的名称。");
        }
        // Windows 设备名即使带扩展名也不应作为展示文件名接受。
        String stem = name.split("\\.", 2)[0].strip();
        if (stem.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])")) {
            throw new MaterialException(400, "INVALID_FILE_NAME", "该文件名为系统保留名称，请重命名。");
        }
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        for (MaterialFileType type : MaterialFileType.values()) {
            if (type.extension().equals(extension)) return new UploadName(name, type);
        }
        throw new MaterialException(415, "FILE_TYPE_UNSUPPORTED", "仅支持 TXT、MD、DOCX 和 PDF 文件。");
    }

    public void validateStructure(Path path, MaterialFileType type) {
        try {
            if (type == MaterialFileType.DOCX) checkDocx(path);
            else if (type == MaterialFileType.PDF) checkPdf(path);
            else checkTextEnvelope(path);
        } catch (ZipException | SAXException | ParserConfigurationException ex) {
            throw invalidStructure();
        } catch (IOException ex) {
            // 无法访问本机文件是存储故障，不能错误地提示用户其资料必然损坏。
            throw new MaterialException(500, "FILE_READ_FAILED", "暂时无法校验文件，请稍后重试。");
        }
    }

    private void checkTextEnvelope(Path path) throws IOException {
        byte[] prefix;
        try (InputStream input = Files.newInputStream(path)) { prefix = input.readNBytes(8); }
        String magic = new String(prefix, StandardCharsets.ISO_8859_1);
        // 只拒绝明确的二进制格式伪装，不在上传层猜测文本编码或静默转码。
        boolean zip = prefix.length >= 4 && prefix[0] == 'P' && prefix[1] == 'K'
                && ((prefix[2] == 3 && prefix[3] == 4) || (prefix[2] == 5 && prefix[3] == 6)
                    || (prefix[2] == 7 && prefix[3] == 8));
        boolean ole = prefix.length >= 4 && (prefix[0] & 255) == 0xd0 && (prefix[1] & 255) == 0xcf
                && (prefix[2] & 255) == 0x11 && (prefix[3] & 255) == 0xe0;
        if (magic.startsWith("%PDF-") || zip || ole) throw invalidStructure();
    }

    private void checkDocx(Path path) throws IOException, SAXException, ParserConfigurationException {
        // 不解压到磁盘，不使用压缩包中的名称构造本机路径。
        try (ZipFile zip = new ZipFile(path.toFile())) {
            ZipEntry contentTypes = zip.getEntry("[Content_Types].xml");
            ZipEntry main = zip.getEntry("word/document.xml");
            if (contentTypes == null || main == null) throw invalidStructure();
            long expanded = 0;
            int count = 0;
            var entries = zip.entries();
            byte[] buffer = new byte[8192];
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (++count > ZIP_ENTRIES_LIMIT) throw expandedLimit();
                String normalized = entry.getName().replace('\\', '/');
                if (normalized.startsWith("/") || normalized.matches("^[A-Za-z]:.*")
                        || java.util.Arrays.asList(normalized.split("/")).contains("..")) {
                    throw invalidStructure();
                }
                if (entry.isDirectory()) continue;
                if (entry.getSize() > ZIP_EXPANDED_LIMIT - expanded) throw expandedLimit();
                try (InputStream input = zip.getInputStream(entry)) {
                    int n;
                    while ((n = input.read(buffer)) != -1) {
                        expanded += n;
                        if (expanded > ZIP_EXPANDED_LIMIT) throw expandedLimit();
                    }
                }
            }
            byte[] types = readXml(zip, contentTypes);
            Document typeDocument = parseXml(types);
            boolean wordDocument = false;
            var overrides = typeDocument.getElementsByTagNameNS(
                    "http://schemas.openxmlformats.org/package/2006/content-types", "Override");
            for (int i = 0; i < overrides.getLength(); i++) {
                var attributes = overrides.item(i).getAttributes();
                var part = attributes.getNamedItem("PartName");
                var content = attributes.getNamedItem("ContentType");
                if (part != null && content != null && "/word/document.xml".equals(part.getNodeValue())
                        && "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"
                            .equals(content.getNodeValue())) wordDocument = true;
            }
            Document document = parseXml(readXml(zip, main));
            if (!wordDocument || !"document".equals(document.getDocumentElement().getLocalName())
                    || !"http://schemas.openxmlformats.org/wordprocessingml/2006/main"
                        .equals(document.getDocumentElement().getNamespaceURI())) throw invalidStructure();
        }
    }

    private byte[] readXml(ZipFile zip, ZipEntry entry) throws IOException {
        if (entry.getSize() > XML_LIMIT) throw expandedLimit();
        try (InputStream input = zip.getInputStream(entry);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = input.read(buffer)) != -1) {
                if (output.size() + n > XML_LIMIT) throw expandedLimit();
                output.write(buffer, 0, n);
            }
            return output.toByteArray();
        }
    }

    private Document parseXml(byte[] bytes) throws ParserConfigurationException, IOException, SAXException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        var builder = factory.newDocumentBuilder();
        // 使用明确的错误处理器，避免解析器把原文或路径直接输出到 stderr。
        builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
            @Override public void error(org.xml.sax.SAXParseException e) throws SAXException { throw e; }
            @Override public void fatalError(org.xml.sax.SAXParseException e) throws SAXException { throw e; }
        });
        return builder.parse(new ByteArrayInputStream(bytes));
    }

    private void checkPdf(Path path) throws IOException {
        long length = Files.size(path);
        if (length < 32) throw invalidStructure();
        byte[] header;
        try (InputStream input = Files.newInputStream(path)) { header = input.readNBytes(8); }
        String magic = new String(header, StandardCharsets.ISO_8859_1);
        if (!magic.matches("%PDF-(1\\.[0-7]|2\\.0)")) throw invalidStructure();
        byte[] tail = new byte[(int) Math.min(length, 4096)];
        try (var channel = java.nio.channels.FileChannel.open(path)) {
            channel.position(length - tail.length);
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(tail);
            while (buffer.hasRemaining() && channel.read(buffer) != -1) { /* 完整读取尾部 */ }
        }
        String end = new String(tail, StandardCharsets.ISO_8859_1);
        if (!end.contains("startxref") || !end.contains("%%EOF")) throw invalidStructure();
        // 这里只查基本封装，不声称完整校验 PDF xref、加密、文字或扫描页。
    }

    private MaterialException invalidStructure() {
        return new MaterialException(415, "INVALID_FILE_STRUCTURE", "文件结构与扩展名不符或文件已损坏。");
    }
    private MaterialException expandedLimit() {
        return new MaterialException(413, "FILE_TOO_LARGE", "文档展开内容超出本阶段处理上限。");
    }
}
