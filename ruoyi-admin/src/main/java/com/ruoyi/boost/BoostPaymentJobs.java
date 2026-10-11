package com.ruoyi.boost;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
/** Database states form a durable outbox. Restarts resume pending money operations. */
@Component @EnableScheduling
public class BoostPaymentJobs {
    private final BoostPayments payments;
    public BoostPaymentJobs(BoostPayments p){payments=p;}
    @Scheduled(initialDelay=30000,fixedDelay=60000) public void reconcile(){payments.reconcile();}
}
