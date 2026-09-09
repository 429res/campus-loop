package edu.campusloop.web.report.service;

import edu.campusloop.common.ApiException;
import org.springframework.dao.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.function.Supplier;

@Component
public class ReportTransactionExecutor {
    private final TransactionTemplate transaction;
    public ReportTransactionExecutor(PlatformTransactionManager manager) {
        transaction=new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setTimeout(20);
    }
    public <T> T execute(Supplier<T> action) {
        for(int attempt=0;attempt<3;attempt++) {
            try {return transaction.execute(ignored->action.get());}
            catch(TransientDataAccessException conflict) {
                if(attempt==2) throw new ApiException(409,"举报操作遇到并发冲突，请使用原请求重试");
            } catch(DataIntegrityViolationException conflict) {
                throw new ApiException(409,"举报数据约束冲突，整次请求已回滚，请刷新后重试");
            }
        }
        throw new IllegalStateException("Unreachable");
    }
}
