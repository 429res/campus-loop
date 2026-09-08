package edu.campusloop.web.exchange.service;

import edu.campusloop.common.ApiException;
import edu.campusloop.exchange.ExchangeCreationCommand;
import edu.campusloop.web.exchange.vo.ExchangeView;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public class ExchangeApplicationService {
    private final ObjectProvider<ExchangeCreationTransaction> creation;
    private final ExchangeQueryService queries;
    public ExchangeApplicationService(ObjectProvider<ExchangeCreationTransaction> creation, ExchangeQueryService queries) {
        this.creation=creation; this.queries=queries;
    }
    public ExchangeView create(long initiatorId, ExchangeCreationCommand command) {
        ExchangeCreationTransaction transaction=creation.getIfAvailable();
        if (transaction == null) throw new ApiException(501, "A-03 创建事务尚未接入；未创建交换或占用物品");
        return queries.detail(initiatorId, transaction.create(initiatorId, command));
    }
}
