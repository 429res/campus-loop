package edu.campusloop.web.report.controller;

import com.fasterxml.jackson.databind.JsonNode;
import edu.campusloop.auth.AuthInterceptor;
import edu.campusloop.common.*;
import edu.campusloop.web.report.dto.*;
import edu.campusloop.web.report.service.ReportService;
import edu.campusloop.web.report.vo.*;
import edu.campusloop.web.user.entity.User;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import java.util.Set;

@RestController
@RequestMapping("/api/admin/reports")
public class AdminReportController {
    private final ReportService reports;
    public AdminReportController(ReportService reports) {this.reports=reports;}
    @ModelAttribute public void privateResponse(jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control","private, no-store");response.addHeader("Vary","Authorization");
    }

    @GetMapping
    public ResultVo<PageResult<ReportSummaryView>> page(@RequestAttribute(AuthInterceptor.USER) User user,
        @RequestParam MultiValueMap<String,String> params) {
        ReportQueryParams.allowed(params,Set.of("page","size","keyword","status","targetType"));
        return ResultVo.success(reports.adminPage(user.getId(),ReportQueryParams.number(params,"page",1),
            ReportQueryParams.number(params,"size",12),params.getFirst("keyword"),params.getFirst("status"),params.getFirst("targetType")));
    }

    @GetMapping("/{id}")
    public ResultVo<ReportDetailView> detail(@RequestAttribute(AuthInterceptor.USER) User user,@PathVariable long id,
        @RequestParam MultiValueMap<String,String> params) {
        ReportQueryParams.allowed(params,Set.of());return ResultVo.success(reports.adminDetail(user.getId(),id));
    }

    @PostMapping("/{id}/accept")
    public ResultVo<ReportDetailView> accept(@RequestAttribute(AuthInterceptor.USER) User user,@PathVariable long id,
        @RequestBody JsonNode body,@RequestParam MultiValueMap<String,String> params) {
        ReportQueryParams.allowed(params,Set.of());return ResultVo.success(reports.accept(user.getId(),id,AcceptReportCommand.parse(body)));
    }

    @PostMapping("/{id}/decision")
    public ResultVo<ReportDetailView> decide(@RequestAttribute(AuthInterceptor.USER) User user,@PathVariable long id,
        @RequestBody JsonNode body,@RequestParam MultiValueMap<String,String> params) {
        ReportQueryParams.allowed(params,Set.of());return ResultVo.success(reports.decide(user.getId(),id,DecideReportCommand.parse(body)));
    }

    @GetMapping("/{id}/audits")
    public ResultVo<PageResult<ReportAuditView>> audits(@RequestAttribute(AuthInterceptor.USER) User user,@PathVariable long id,
        @RequestParam MultiValueMap<String,String> params) {
        ReportQueryParams.allowed(params,Set.of("page","size"));return ResultVo.success(reports.audits(user.getId(),id,
            ReportQueryParams.number(params,"page",1),ReportQueryParams.number(params,"size",12)));
    }

    @GetMapping("/{reportId}/evidence/{evidenceId}/content")
    public ResponseEntity<ByteArrayResource> evidence(@RequestAttribute(AuthInterceptor.USER) User user,@PathVariable long reportId,
        @PathVariable long evidenceId,@RequestParam MultiValueMap<String,String> params) {
        ReportQueryParams.allowed(params,Set.of());return ReportController.content(reports.adminEvidence(user.getId(),reportId,evidenceId));
    }
}
