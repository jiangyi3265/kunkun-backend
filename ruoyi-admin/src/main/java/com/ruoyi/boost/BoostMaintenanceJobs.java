package com.ruoyi.boost;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class BoostMaintenanceJobs {
    private final BoostMediaFiles media;private final BoostDb db;
    public BoostMaintenanceJobs(BoostMediaFiles m,BoostDb d){media=m;db=d;}
    @Scheduled(initialDelay=120000,fixedDelay=600000) public void collect(){
        try{media.collectOrphans();db.run("DELETE FROM boost_rate_limit WHERE resets_at<CURRENT_TIMESTAMP");db.run("DELETE FROM boost_phone_challenge WHERE expires_at<?",java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(86400)));}
        catch(Exception e){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Asset maintenance failed: {}",e.getClass().getSimpleName());}
    }
}
