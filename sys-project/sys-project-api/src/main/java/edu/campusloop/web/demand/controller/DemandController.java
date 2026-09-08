package edu.campusloop.web.demand.controller;

import edu.campusloop.auth.AuthInterceptor;
import edu.campusloop.common.PageResult;
import edu.campusloop.common.ResultVo;
import edu.campusloop.web.demand.dto.*;
import edu.campusloop.web.demand.service.DemandService;
import edu.campusloop.web.demand.vo.*;
import edu.campusloop.web.user.entity.User;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/demands")
public class DemandController {
    private final DemandService demands;

    public DemandController(DemandService demands) { this.demands = demands; }

    @PostMapping
    public ResultVo<DemandView> create(@RequestAttribute(AuthInterceptor.USER) User user,
                                       @Valid @RequestBody CreateDemandRequest body) {
        return ResultVo.success(demands.create(user.getId(), body));
    }

    @GetMapping
    public ResultVo<PageResult<DemandView>> page(@RequestAttribute(AuthInterceptor.USER) User user,
                                                @RequestParam(defaultValue = "1") int page,
                                                @RequestParam(defaultValue = "12") int size,
                                                @RequestParam(required = false) String status) {
        return ResultVo.success(demands.page(user.getId(), page, size, status));
    }

    @GetMapping("/offerable-items")
    public ResultVo<PageResult<OfferedItemView>> offerableItems(@RequestAttribute(AuthInterceptor.USER) User user,
                                                              @RequestParam(defaultValue = "1") int page,
                                                              @RequestParam(defaultValue = "12") int size) {
        return ResultVo.success(demands.offerableItems(user.getId(), page, size));
    }

    @GetMapping("/{id}")
    public ResultVo<DemandView> detail(@RequestAttribute(AuthInterceptor.USER) User user, @PathVariable long id) {
        return ResultVo.success(demands.detail(user.getId(), id));
    }

    @PatchMapping("/{id}")
    public ResultVo<DemandView> patch(@RequestAttribute(AuthInterceptor.USER) User user, @PathVariable long id,
                                      @Valid @RequestBody PatchDemandRequest body) {
        return ResultVo.success(demands.patch(user.getId(), id, body));
    }

    @PatchMapping("/{id}/status")
    public ResultVo<DemandView> status(@RequestAttribute(AuthInterceptor.USER) User user, @PathVariable long id,
                                       @Valid @RequestBody DemandStatusRequest body) {
        return ResultVo.success(demands.changeStatus(user.getId(), id, body));
    }

    @DeleteMapping("/{id}")
    public ResultVo<DeletedDemandView> delete(@RequestAttribute(AuthInterceptor.USER) User user,
                                              @PathVariable long id,
                                              @RequestParam(required = false) Integer version) {
        return ResultVo.success(demands.delete(user.getId(), id, version));
    }
}
