package edu.campusloop.web.exchange.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import edu.campusloop.common.*;
import edu.campusloop.web.exchange.entity.*;
import edu.campusloop.web.exchange.mapper.ExchangeMapper;
import edu.campusloop.web.exchange.vo.ExchangeView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
public class ExchangeQueryService {
    public static final Set<String> STATUSES=Set.of("AWAITING_CONFIRMATION","READY","COMPLETED","CANCELLED","EXPIRED","DISPUTED");
    private final ExchangeMapper exchanges;
    public ExchangeQueryService(ExchangeMapper exchanges) { this.exchanges=exchanges; }

    public PageResult<ExchangeView> mine(long userId,int page,int size,String status) {
        if (page<1 || size<1 || size>100 || (status!=null && !STATUSES.contains(status)))
            throw new ApiException(400,"分页或交换状态参数不正确");
        QueryWrapper<ExchangeRecord> query=visible(userId);
        if (status!=null) query.eq("status",status);
        query.orderByDesc("created_at","id");
        Page<ExchangeRecord> result=exchanges.selectPage(new Page<>(page,size),query);
        return new PageResult<>(views(result.getRecords()),result.getTotal(),page,size);
    }
    public ExchangeView detail(long userId,long id) {
        if (id<1) throw new ApiException(400,"交换ID须为正整数");
        ExchangeRecord found=exchanges.selectOne(visible(userId).eq("id",id));
        if (found==null) throw new ApiException(404,"交换不存在或不可见");
        return views(List.of(found)).get(0);
    }
    private QueryWrapper<ExchangeRecord> visible(long userId) {
        if (userId<1) throw new ApiException(403,"用户身份无效");
        return new QueryWrapper<ExchangeRecord>().exists("SELECT 1 FROM cl_exchange_participant p " +
            "WHERE p.exchange_id=cl_exchange.id AND p.user_id={0}",userId);
    }
    private List<ExchangeView> views(List<ExchangeRecord> rows) {
        if (rows.isEmpty()) return List.of();
        Map<Long,List<ExchangeParticipantRecord>> grouped=exchanges.participants(rows.stream().map(ExchangeRecord::getId).toList())
            .stream().collect(Collectors.groupingBy(ExchangeParticipantRecord::getExchangeId));
        return rows.stream().map(row -> view(row,grouped.getOrDefault(row.getId(),List.of()))).toList();
    }
    private ExchangeView view(ExchangeRecord row,List<ExchangeParticipantRecord> people) {
        if (people.size()<2 || people.size()>3) throw incomplete();
        Map<Long,ExchangeParticipantRecord> byUser=new HashMap<>();
        Map<Long,Long> incoming=new HashMap<>();
        Set<Long> itemIds=new HashSet<>();
        for (var p:people) {
            if (byUser.put(p.getUserId(),p)!=null || !itemIds.add(p.getOfferedItemId()) ||
                p.getUserId().equals(p.getRecipientUserId()) || incoming.put(p.getRecipientUserId(),p.getOfferedItemId())!=null)
                throw incomplete();
        }
        if (!incoming.keySet().equals(byUser.keySet()) || !byUser.containsKey(row.getInitiatorId())) throw incomplete();
        // With two or three distinct users and no self edges, this is exactly one directed cycle.
        List<ExchangeParticipantRecord> ordered=new ArrayList<>();
        var p=people.stream().min(Comparator.comparingLong(ExchangeParticipantRecord::getOfferedItemId)).orElseThrow();
        for (int i=0;i<people.size();i++) { ordered.add(p); p=byUser.get(p.getRecipientUserId()); }
        return new ExchangeView(row.getId(),row.getInitiatorId(),row.getStatus(),row.getVersion(),
            utc(row.getCreatedAt()),utc(row.getExpiresAt()),ordered.stream().map(person -> new ExchangeView.Participant(
                person.getUserId(),person.getDisplayName(),person.getOfferedItemId(),incoming.get(person.getUserId()),
                person.getConfirmedAt()==null?"PENDING":"CONFIRMED",utc(person.getConfirmedAt()),
                utc(person.getHandedOffAt()),utc(person.getReceivedAt()))).toList(),
            ordered.stream().map(person -> new ExchangeView.Flow(person.getOfferedItemId(),person.getUserId(),person.getRecipientUserId())).toList(),
            List.of()); // No mutation is implemented: expiration and role must never manufacture permissions.
    }
    private static Instant utc(LocalDateTime value) { return value==null?null:value.toInstant(ZoneOffset.UTC); }
    private static ApiException incomplete() { return new ApiException(409,"交换记录不完整，请联系维护人员"); }
}
