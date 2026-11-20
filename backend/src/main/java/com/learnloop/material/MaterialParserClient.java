package com.learnloop.material;

import com.learnloop.config.MaterialParseProperties;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import org.springframework.stereotype.Component;

/** JDK HttpClient 流式发送原件；响应接收时限长，而不是全部读完才检查大小。 */
@Component
public class MaterialParserClient {
    static final int MAX_RESPONSE = 2*1024*1024;
    private final MaterialParseProperties properties;
    private final ParserResultValidator validator;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER).build();
    public MaterialParserClient(MaterialParseProperties properties, ParserResultValidator validator) {
        this.properties=properties; this.validator=validator;
    }
    public ParserResultValidator.Result parse(Path file,String type,String attempt) {
        try {
            if(properties.getServiceKey().length()<32) throw new MaterialException(503,"PARSER_NOT_CONFIGURED","内部解析凭据尚未配置。");
            URI base=URI.create(properties.getBaseUrl());
            if(!"http".equals(base.getScheme()) || !"127.0.0.1".equals(base.getHost()) || base.getPort()!=8001
                    || base.getUserInfo()!=null || base.getQuery()!=null || base.getFragment()!=null
                    || !(base.getPath().isEmpty() || base.getPath().equals("/"))) {
                throw new MaterialException(503,"PARSER_NOT_CONFIGURED","解析地址必须是本机 8001 端口。");
            }
            String boundary="LearnLoop"+UUID.randomUUID().toString().replace("-","");
            String prefix="--"+boundary+"\r\nContent-Disposition: form-data; name=\"fileType\"\r\n\r\n"+type
                +"\r\n--"+boundary+"\r\nContent-Disposition: form-data; name=\"attemptId\"\r\n\r\n"+attempt
                +"\r\n--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"material.bin\"\r\nContent-Type: application/octet-stream\r\n\r\n";
            var body=HttpRequest.BodyPublishers.concat(HttpRequest.BodyPublishers.ofString(prefix),
                    HttpRequest.BodyPublishers.ofFile(file),HttpRequest.BodyPublishers.ofString("\r\n--"+boundary+"--\r\n"));
            HttpRequest request=HttpRequest.newBuilder(base.resolve("/internal/parse"))
                    .timeout(Duration.ofSeconds(40)).header("X-LearnLoop-Service-Key",properties.getServiceKey())
                    .header("Content-Type","multipart/form-data; boundary="+boundary).POST(body).build();
            // future 的 40 秒截止覆盖接收正文，不能只依赖收到响应头之前的超时。
            var future=http.sendAsync(request,info->new LimitedBody());
            HttpResponse<byte[]> response;
            try { response=future.get(40,TimeUnit.SECONDS); }
            catch(TimeoutException ex) { future.cancel(true); throw new MaterialException(504,"PARSER_TIMEOUT","解析调用超时，请稍后重试。"); }
            catch(InterruptedException ex) { future.cancel(true);throw ex; }
            catch(ExecutionException ex) {
                if(ex.getCause() instanceof MaterialException me) throw me;
                if(ex.getCause() instanceof HttpTimeoutException) throw new MaterialException(504,"PARSER_TIMEOUT","解析调用超时，请稍后重试。");
                throw ex;
            }
            if(response.statusCode()!=200) throw safeError(response.statusCode(),response.body(),type);
            return validator.validate(new String(response.body(),StandardCharsets.UTF_8),type,attempt);
        } catch(MaterialException ex) { throw ex; }
        catch(InterruptedException ex) { Thread.currentThread().interrupt(); throw new MaterialException(503,"PARSER_INTERRUPTED","处理被中断，请重试。"); }
        catch(Exception ex) { throw new MaterialException(503,"PARSER_UNAVAILABLE","解析服务不可用，请启动 Python 服务后重试。"); }
    }
    private MaterialException safeError(int status,byte[] body,String type) {
        // 不将内部服务任意错误全文转发给网页，只映射已知稳定错误码。
        if(status==401 || status==403) return new MaterialException(503,"PARSER_AUTH_FAILED","内部解析凭据不匹配，请检查服务配置。");
        try {
            var json=tools.jackson.databind.json.JsonMapper.builder().build();
            Map<?,?> value=json.readValue(body,Map.class);
            String code=String.valueOf(value.get("errorCode"));
            Map<String,String> allowed=Map.ofEntries(
                Map.entry("INVALID_ENCODING","文本必须为 UTF-8 编码，请转换后重新上传。"),
                Map.entry("EMPTY_TEXT","文件没有有效文字。"),Map.entry("NO_EXTRACTABLE_TEXT","未提取到可用文字，可能含扫描内容；当前未启用 OCR。"),
                Map.entry("PDF_ENCRYPTED","当前不支持加密 PDF。"),Map.entry("INVALID_PDF","PDF 损坏或格式不受支持。"),
                Map.entry("INVALID_DOCX","Word 损坏或格式不受支持。"),Map.entry("INVALID_TEXT","内容与文本格式不符。"),
                Map.entry("TEXT_LIMIT_EXCEEDED","文字超过 "+ParserResultValidator.maxTextCharacters(type)+" 字符，请拆分资料。"),
                Map.entry("BLOCK_LIMIT_EXCEEDED","正文块过多，请拆分资料。"),Map.entry("DOCX_LIMIT_EXCEEDED","Word 展开内容超出限制。"),
                Map.entry("PDF_PAGE_LIMIT_EXCEEDED","PDF 超过 "+ParserResultValidator.MAX_PDF_PAGES+" 页，请拆分资料。"),
                Map.entry("RESULT_TOO_LARGE","解析结果过大，请拆分资料。"),
                Map.entry("PARSER_BUSY","解析服务正忙，请稍后重试。"),Map.entry("PARSER_TIMEOUT","解析超时，请拆分资料后重试。"));
            if(allowed.containsKey(code)) return new MaterialException(502,code,allowed.get(code));
        } catch(RuntimeException ignored) {}
        return new MaterialException(502,"PARSER_FAILED","解析服务未能完成此文件，请检查资料后重试。");
    }
    static class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result=new CompletableFuture<>();
        private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody(){return result;}
        public void onSubscribe(Flow.Subscription value){subscription=value;value.request(1);}
        public void onNext(List<ByteBuffer> chunks){
            for(var chunk:chunks){
                if(chunk.remaining()>MAX_RESPONSE-bytes.size()){
                    subscription.cancel();result.completeExceptionally(new MaterialException(502,"PARSER_INVALID_RESULT","解析返回内容超出上限，请拆分资料。"));return;
                }
                byte[] part=new byte[chunk.remaining()];chunk.get(part);bytes.writeBytes(part);
            }
            subscription.request(1);
        }
        public void onError(Throwable error){result.completeExceptionally(error);}
        public void onComplete(){result.complete(bytes.toByteArray());}
    }
}
