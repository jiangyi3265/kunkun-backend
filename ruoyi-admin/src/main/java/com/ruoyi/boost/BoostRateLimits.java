package com.ruoyi.boost;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static com.ruoyi.boost.BoostDb.*;

/** Database backed, so failed transactions and application restarts cannot reset the limit. */
@Service
public class BoostRateLimits {
    private final BoostDb db;
    public BoostRateLimits(BoostDb db){this.db=db;}
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void hit(String key,int maximum,int seconds){
        db.one("SELECT config_value FROM boost_config WHERE config_key='rate:lock' FOR UPDATE");
        String bucket=BoostIdempotency.digest(map("key",key));
        List<Map<String,Object>> rows=db.list("SELECT hits FROM boost_rate_limit WHERE bucket_key=? AND resets_at>CURRENT_TIMESTAMP",bucket);
        if(rows.isEmpty()){
            db.run("DELETE FROM boost_rate_limit WHERE bucket_key=?",bucket);
            db.run("INSERT INTO boost_rate_limit(bucket_key,hits,resets_at) VALUES (?,1,?)",bucket,Timestamp.from(Instant.now().plusSeconds(seconds)));
        }else{
            if(num(rows.get(0),"hits")>=maximum)throw new BoostException(429,"尝试次数过多，请稍后重试");
            db.run("UPDATE boost_rate_limit SET hits=hits+1 WHERE bucket_key=?",bucket);
        }
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void clear(String key){db.run("DELETE FROM boost_rate_limit WHERE bucket_key=?",BoostIdempotency.digest(map("key",key)));}
}
