package com.learnloop.material;

import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** 对内部服务也做边界校验，避免错误版本、异常体积和伪造定位进入业务表。 */
@Component
public class ParserResultValidator {
    static final int MAX_PDF_PAGES = 1000;

    static int maxTextCharacters(String type) {
        return "pdf".equals(type) ? 2_000_000 : 200_000;
    }
    private final JsonMapper json = JsonMapper.builder().build();
    public record Block(int number, String type, String text, Map<String,Object> source,String noiseJson) {
        public Block(int number,String type,String text,Map<String,Object> source) {this(number,type,text,source,null);}
    }
    public record Result(String version, List<Block> blocks, int characters, String warningsJson) {}

    public Result validate(String body, String type, String attempt) {
        // 解析服务返回结果的边界检查
        try {
            Map<String,Object> value = json.readValue(body, new TypeReference<>() {});//
            if (!value.keySet().equals(Set.of("schemaVersion","attemptId","fileType","parserVersion","blocks","textCharCount","warnings"))) bad();
            if (integer(value.get("schemaVersion"),1,1) != 1 || !attempt.equals(value.get("attemptId")) || !type.equals(value.get("fileType"))) bad();
            String version = string(value.get("parserVersion"),64);
            if (!version.matches("learnloop-parser/[0-9]+\\.[0-9]+\\.[0-9]+")) bad();
            // 按资料格式限制正文总量，同时核对逐块累计值与 Python 声明的字数。
            int characterLimit = maxTextCharacters(type);
            List<?> raw = list(value.get("blocks"),2000);
            if (raw.isEmpty()) bad();
            List<Block> blocks = new ArrayList<>();
            int total = 0;
            for (Object item : raw) {
                Map<String,Object> b = object(item);
                if (!b.keySet().equals(Set.of("blockNo","blockType","text","sourceLocator"))
                        && !b.keySet().equals(Set.of("blockNo","blockType","text","sourceLocator","noiseRanges"))) bad();
                int number = integer(b.get("blockNo"),1,2000);
                if (number != blocks.size()+1) bad();
                String text = string(b.get("text"),4000);
                if (text.isBlank() || text.indexOf('\0') >= 0 || text.indexOf('\r') >= 0) bad();
                total += text.codePointCount(0,text.length());
                if (total > characterLimit) bad();
                String blockType = string(b.get("blockType"),16);
                Map<String,Object> source = object(b.get("sourceLocator"));
                integer(source.get("partIndex"),1,2000);
                if (type.equals("txt") || type.equals("md")) {
                    if (!blockType.equals("text") || !"text".equals(source.get("kind")) || !source.keySet().equals(Set.of("kind","lineStart","lineEnd","partIndex"))) bad();
                    int start = integer(source.get("lineStart"),1,2097153);
                    integer(source.get("lineEnd"),start,2097153);
                } else if (type.equals("pdf")) {
                    if (!blockType.equals("pdf_text") || !"pdf".equals(source.get("kind")) || !source.keySet().equals(Set.of("kind","pageNumber","partIndex"))) bad();
                    integer(source.get("pageNumber"),1,MAX_PDF_PAGES);
                } else if (type.equals("docx")) {
                    if (!"docx".equals(source.get("kind"))) bad();
                    Set<String> fields = blockType.equals("table_row") ? Set.of("kind","bodyBlockIndex","tableRowIndex","partIndex") : Set.of("kind","bodyBlockIndex","partIndex");
                    if (!Set.of("paragraph","table_row").contains(blockType) || !source.keySet().equals(fields)) bad();
                    integer(source.get("bodyBlockIndex"),1,1000000);
                    if (blockType.equals("table_row")) integer(source.get("tableRowIndex"),1,1000000);
                } else bad();
                List<?> noise=b.containsKey("noiseRanges")?list(b.get("noiseRanges"),1000):List.of();
                int previousEnd=0;
                for(Object range:noise) {
                    List<?> pair=list(range,2);
                    if(pair.size()!=2 || !"pdf".equals(type)) bad();
                    int start=integer(pair.get(0),previousEnd,text.codePointCount(0,text.length())-1);
                    previousEnd=integer(pair.get(1),start+1,text.codePointCount(0,text.length()));
                }
                blocks.add(new Block(number,blockType,text,Map.copyOf(source),noise.isEmpty()?null:json.writeValueAsString(noise)));
            }
            if (integer(value.get("textCharCount"),1,characterLimit) != total) bad();
            List<?> warnings = list(value.get("warnings"),20);
            for (Object warning : warnings) {
                Map<String,Object> w = object(warning);
                if (!w.keySet().equals(Set.of("code","message","pageNumbers"))) bad();
                if (!string(w.get("code"),64).matches("[A-Z][A-Z0-9_]*")) bad();
                string(w.get("message"),255);
                for (Object p : list(w.get("pageNumbers"),MAX_PDF_PAGES)) integer(p,1,MAX_PDF_PAGES);
            }
            return new Result(version,List.copyOf(blocks),total,json.writeValueAsString(warnings));
        } catch (MaterialException ex) { throw ex; }
        catch (RuntimeException ex) { throw invalid(); }
    }
    private static void bad() { throw invalid(); }
    private static MaterialException invalid() { return new MaterialException(502,"PARSER_INVALID_RESULT","解析服务返回了无效结果，请重试或检查服务版本。"); }
    @SuppressWarnings("unchecked")
    private static Map<String,Object> object(Object v) { if (!(v instanceof Map<?,?>)) bad(); return (Map<String,Object>)v; }
    private static List<?> list(Object v,int max) { if (!(v instanceof List<?> l) || l.size()>max) bad(); return (List<?>)v; }
    private static int integer(Object v,int min,int max) { if (!(v instanceof Integer i) || i<min || i>max) bad(); return (Integer)v; }
    private static String string(Object v,int max) {
        if (!(v instanceof String s) || s.isEmpty() || s.codePointCount(0,s.length())>max) bad();
        String s=(String)v;
        // 拒绝未配对 UTF-16 代理字符，避免数据库编码时悄悄替换内容。
        for(int i=0;i<s.length();i++) if(Character.isSurrogate(s.charAt(i))) {
            if(!Character.isHighSurrogate(s.charAt(i)) || i+1>=s.length() || !Character.isLowSurrogate(s.charAt(++i))) bad();
        }
        return s;
    }
}
