package com.maipiao.order.service;

import io.seata.core.model.GlobalStatus;
import io.seata.tm.api.GlobalTransaction;
import io.seata.tm.api.GlobalTransactionContext;
import org.springframework.stereotype.Component;

/** 留出可测试的 TC 边界；Finished/查不到不是成功或回滚的证明。 */
@Component
public class CreationTransactions {
    public GlobalTransaction begin() throws Exception {
        GlobalTransaction tx = GlobalTransactionContext.createNew();
        tx.begin(30000, "create-order-reservation");
        return tx;
    }

    public GlobalStatus status(String xid) throws Exception {
        return GlobalTransactionContext.reload(xid).getStatus();
    }
}
