package com.ruoyi.boost;
import com.ruoyi.common.annotation.Anonymous;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import static com.ruoyi.boost.BoostDb.*;
@RestController public class BoostHealth {
    private final BoostDb db;private final StringRedisTemplate redis;
    public BoostHealth(BoostDb d,StringRedisTemplate r){db=d;redis=r;}
    @Anonymous @GetMapping("/health/ready") public ResponseEntity<Map<String,Object>> ready(){
        try{
            // A TCP connection/SELECT 1 cannot prove the application's business schema is usable.
            db.count("SELECT COUNT(*) FROM boost_order o JOIN boost_member m ON m.id=o.buyer_id JOIN boost_listing l ON l.id=o.listing_id JOIN boost_session s ON s.member_id=m.id JOIN boost_media a ON a.owner_id=m.id JOIN sys_user u ON u.user_id=-1 WHERE 1=0");
            String pong=redis.execute((org.springframework.data.redis.core.RedisCallback<String>)c->c.ping());
            if(!"PONG".equals(pong))return ResponseEntity.status(503).body(map("status","DOWN"));
            return ResponseEntity.ok(map("status","UP"));
        }
        catch(Exception e){return ResponseEntity.status(503).body(map("status","DOWN"));}
    }
}
