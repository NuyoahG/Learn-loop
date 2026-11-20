package com.learnloop.material;

import com.learnloop.auth.LoginUser;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartHttpServletRequest;

/**
 * 资料只通过 Java 入口访问。身份从 Session 得到，不读取表单里的 userId。
 * 上传与解析分开；解析认领后立即返回，客户端通过详情查询状态。
 */
@RestController
@RequestMapping("/api/materials")
public class MaterialController {
    private final MaterialService service;
    private final MaterialParsingService parsing;
    public MaterialController(MaterialService service, MaterialParsingService parsing) {
        this.service = service; this.parsing = parsing;
    }

    @PostMapping("/{id}/parse")
    public ResponseEntity<MaterialResponse> parse(@AuthenticationPrincipal LoginUser user,@PathVariable("id") Long id) {
        var reply=parsing.start(user.getUserId(),id);
        return ResponseEntity.status(reply.status()).body(reply.material());
    }

    @GetMapping("/{id}/blocks")
    public MaterialParsingService.BlockPage blocks(@AuthenticationPrincipal LoginUser user,@PathVariable("id") Long id,
            @RequestParam(name="page",defaultValue="1") int page,@RequestParam(name="size",defaultValue="10") int size) {
        return parsing.blocks(user.getUserId(),id,page,size);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<MaterialResponse> upload(
            @AuthenticationPrincipal LoginUser user, MultipartHttpServletRequest request) {
        int fileCount = request.getMultiFileMap().values().stream().mapToInt(java.util.List::size).sum();
        if (fileCount != 1 || request.getFiles("file").size() != 1
                || request.getParameterValues("requestId") == null
                || request.getParameterValues("requestId").length != 1) {
            throw new MaterialException(400, "INVALID_UPLOAD_FORM", "请使用 file 和 requestId 上传一份文件。");
        }
        MaterialService.UploadResult result = service.upload(
                user.getUserId(), request.getFile("file"), request.getParameter("requestId"));
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.material());
    }

    @GetMapping
    public MaterialPageResponse page(@AuthenticationPrincipal LoginUser user,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "size", defaultValue = "10") int size) {
        return service.page(user.getUserId(), page, size);
    }

    @GetMapping("/{id}")
    public MaterialResponse detail(@AuthenticationPrincipal LoginUser user,
            @PathVariable("id") Long id) {
        return service.detail(user.getUserId(), id);
    }

    @GetMapping("/{id}/original")
    public ResponseEntity<Resource> original(@AuthenticationPrincipal LoginUser user, @PathVariable("id") Long id) {
        var original = service.original(user.getUserId(), id);
        var contentType = switch (original.type()) {
            case "pdf" -> MediaType.APPLICATION_PDF;
            case "docx" -> MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
            default -> new MediaType("text", "plain", StandardCharsets.UTF_8);
        };
        // 只读原件来自当前账号私有存储。浏览器用程序化读取预览，直开 URL 走下载。
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(contentType)
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(original.name(), StandardCharsets.UTF_8).build().toString())
                .body(new FileSystemResource(original.path()));
    }
}
