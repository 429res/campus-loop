package edu.campusloop.web.exchange.service;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import edu.campusloop.common.*;
import edu.campusloop.web.exchange.entity.ExchangeRecord;
import edu.campusloop.web.exchange.mapper.*;
import edu.campusloop.web.exchange.vo.*;
import edu.campusloop.web.user.mapper.UserMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.ZoneOffset;
import java.util.List;

/** Read-only C-04 boundary. No intake, evidence authorization or adjudication capability. */
@Service
@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class ExchangeDisputeQueryService {
    private final ExchangeMapper exchanges;
    private final ExchangeDisputeQueryMapper events;
    private final ExchangeQueryService projection;
    private final UserMapper users;
    public ExchangeDisputeQueryService(ExchangeMapper exchanges,ExchangeDisputeQueryMapper events,
        ExchangeQueryService projection,UserMapper users) {
        this.exchanges=exchanges;this.events=events;this.projection=projection;this.users=users;
    }
    public PageResult<ExchangeView> page(long actor,int page,int size) {
        admin(actor);pagination(page,size);
        var result=exchanges.selectPage(new Page<ExchangeRecord>(page,size),
            new QueryWrapper<ExchangeRecord>().eq("status","DISPUTED").orderByDesc("disputed_at","id"));
        return new PageResult<>(projection.adminViews(result.getRecords()),result.getTotal(),page,size);
    }
    public ExchangeView detail(long actor,long id) {
        admin(actor);return projection.adminViews(List.of(dispute(id))).get(0);
    }
    public PageResult<ExchangeEventView> events(long actor,long id,int page,int size) {
        admin(actor);pagination(page,size);dispute(id);
        return new PageResult<>(events.page(id,size,((long)page-1)*size).stream().map(e->
            new ExchangeEventView(e.id(),e.exchangeId(),e.actorId(),e.eventType(),e.previousStatus(),e.newStatus(),
                e.previousVersion(),e.newVersion(),e.reason(),e.occurredAt().toInstant(ZoneOffset.UTC))).toList(),
            events.count(id),page,size);
    }
    private ExchangeRecord dispute(long id) {
        if(id<1) throw new ApiException(400,"交换ID须为正整数");
        var row=exchanges.selectOne(new QueryWrapper<ExchangeRecord>().eq("id",id).eq("status","DISPUTED"));
        if(row==null) throw new ApiException(404,"争议交换不存在");
        return row;
    }
    private void admin(long actor) {
        var user=users.selectById(actor);
        if(user==null || !"ACTIVE".equals(user.getStatus())) throw new ApiException(401,"登录身份已失效");
        if(!"ADMIN".equals(user.getRole())) throw new ApiException(403,"需要管理员权限");
    }
    private static void pagination(int page,int size) {
        if(page<1 || size<1 || size>100) throw new ApiException(400,"分页参数不正确");
    }
}
