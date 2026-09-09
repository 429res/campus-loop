package edu.campusloop.web.report.controller;

import com.fasterxml.jackson.databind.JsonNode;
import edu.campusloop.auth.AuthInterceptor;
import edu.campusloop.common.*;
import edu.campusloop.web.report.dto.CreateReportCommand;
import edu.campusloop.web.report.service.ReportService;
import edu.campusloop.web.report.vo.*;
import edu.campusloop.web.user.entity.User;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import java.util.Set;

@RestController
@RequestMapping("/api/reports")
public class ReportController {
    private final ReportService reports;
    public ReportController(ReportService reports) {this.reports=reports;}
    @ModelAttribute public void privateResponse(jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control","private, no-store");response.addHeader("Vary","Authorization");
    }

    @PostMapping
    public ResultVo<ReportDetailView> create(@RequestAttribute(AuthInterceptor.USER) User user,@RequestBody JsonNode body,
        @RequestParam MultiValueMap<String,String> params) {
        ReportQueryParams.allowed(params,Set.of());return ResultVo.success(reports.create(user.getId(),CreateReportCommand.parse(body)));
    }

    @GetMapping("/mine")
    public ResultVo<PageResult<ReportSummaryView>> mine(@RequestAttribute(AuthInterceptor.USER) User user,
        @RequestParam MultiValueMap<String,String> params) {
        ReportQueryParams.allowed(params,Set.of("page","size","status","targetType"));
        return ResultVo.success(reports.minePage(user.getId(),ReportQueryParams.number(params,"page",1),
            ReportQueryParams.number(params,"size",12),params.getFirst("status"),params.getFirst("targetType")));
    }

    @GetMapping("/mine/{id}")
    public ResultVo<ReportDetailView> mineDetail(@RequestAttribute(AuthInterceptor.USER) User user,@PathVariable long id,
        @RequestParam MultiValueMap<String,String> params) {
        ReportQueryParams.allowed(params,Set.of());return ResultVo.success(reports.mineDetail(user.getId(),id));
    }

    @GetMapping("/mine/{reportId}/evidence/{evidenceId}/content")
    public ResponseEntity<ByteArrayResource> mineEvidence(@RequestAttribute(AuthInterceptor.USER) User user,
        @PathVariable long reportId,@PathVariable long evidenceId,@RequestParam MultiValueMap<String,String> params) {
        ReportQueryParams.allowed(params,Set.of());return content(reports.mineEvidence(user.getId(),reportId,evidenceId));
    }

    static ResponseEntity<ByteArrayResource> content(ReportService.EvidenceContent content) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(content.contentType())).contentLength(content.bytes().length)
            .cacheControl(CacheControl.noStore().cachePrivate()).header("X-Content-Type-Options","nosniff")
            .body(new ByteArrayResource(content.bytes()));
    }
}
