package com.learnloop.journal;

import java.util.List;

/** 坐标以 PDF 原件页的宽高为 1 归一化，浏览器缩放只影响回显。 */
public final class PdfAnnotationApi {
    private PdfAnnotationApi() {}
    public record Rect(double x, double y, double width, double height) {}
    public record Position(int pageNumber, List<Rect> rects) {}
    public record Create(String id, int pageNumber, String quote, List<Rect> rects, String color, String note) {}
    public record Edit(String revision, String color, String note, String noteRevision) {}
    public record Annotation(String id, int pageNumber, String quote, List<Rect> rects, String color,
                             String revision, String noteId, String note, String noteRevision) {}
    public record Annotations(List<Annotation> records) {}
    public record Row(String id, int pageNumber, String quote, String rects, String color,
                      String revision, String createHash, boolean deleted,
                      String noteId, String note, String noteRevision) {}
}
