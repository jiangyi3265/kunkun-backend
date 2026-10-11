package com.ruoyi.boost;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static com.ruoyi.boost.BoostDb.str;

/** Daily order serials, allocated in the same database transaction as the order. */
final class BoostOrderNumbers {
    private static final ZoneId BUSINESS_ZONE=ZoneId.of("Asia/Shanghai");
    private BoostOrderNumbers(){}

    static String next(BoostDb db,Clock clock){
        if(!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Order numbers require an active order transaction");
        String prefix="G"+LocalDate.now(clock.withZone(BUSINESS_ZONE)).format(DateTimeFormatter.BASIC_ISO_DATE);
        String key="sequence:order:"+prefix;
        // The unique config key serializes first-use as well as existing-day allocations
        // across JVMs. A rollback also rolls back the serial; restarts do not reset it.
        db.run("INSERT INTO boost_config(config_key,config_value) VALUES (?,?) ON DUPLICATE KEY UPDATE config_key=config_key",key,"0");
        long last=Long.parseLong(str(db.one("SELECT config_value FROM boost_config WHERE config_key=? FOR UPDATE",key),"configValue"));
        if(last<0||last>9999)throw new IllegalStateException("Invalid persisted order serial");
        if(last==9999)throw new BoostException(503,"当天订单编号已用完，请联系平台处理");
        long next=last+1;
        db.run("UPDATE boost_config SET config_value=? WHERE config_key=?",Long.toString(next),key);
        return prefix+String.format(Locale.ROOT,"%04d",next);
    }
}
