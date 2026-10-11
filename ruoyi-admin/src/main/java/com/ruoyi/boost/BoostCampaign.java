package com.ruoyi.boost;
import java.sql.Timestamp;
import java.time.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.ruoyi.boost.BoostDb.*;

/** Real-money newcomer subsidies require a unique WeChat identity and an explicit budget. */
@Service
public class BoostCampaign {
    private final BoostDb db;
    @Value("${boost.campaign.daily-budget-cents:0}") private long dailyBudget;
    @Value("${boost.campaign.total-budget-cents:0}") private long totalBudget;
    public BoostCampaign(BoostDb db){this.db=db;}
    @Transactional public void claim(long member,String openid){
        if(dailyBudget<1000||totalBudget<1000)return;
        String identity=BoostIdempotency.digest(map("openid",openid));
        db.one("SELECT config_value FROM boost_config WHERE config_key='campaign:lock' FOR UPDATE");
        if(!db.list("SELECT coupon_id FROM boost_campaign_claim WHERE identity_hash=? OR member_id=? FOR UPDATE",identity,member).isEmpty())return;
        String day=LocalDate.now(ZoneOffset.UTC).toString();
        // Callers may already have a REPEATABLE READ snapshot. Read current locked budget rows after the mutex.
        java.util.List<java.util.Map<String,Object>> budgets=db.list("SELECT budget_day,used_cents FROM boost_campaign_budget FOR UPDATE");
        long used=0,total=0;boolean dayExists=false;
        for(java.util.Map<String,Object> row:budgets){long amount=num(row,"usedCents");total=Math.addExact(total,amount);if(day.equals(str(row,"budgetDay"))){used=amount;dayExists=true;}}
        if(used+1000>dailyBudget||total+1000>totalBudget)return;
        if(!dayExists)db.run("INSERT INTO boost_campaign_budget(budget_day,used_cents) VALUES (?,0)",day);
        long coupon=db.insert("INSERT INTO boost_coupon(member_id,title,discount_cents,minimum_cents,expires_at) VALUES (?,'新人专享券',1000,10000,?)",member,Timestamp.from(Instant.now().plusSeconds(30*86400)));
        db.run("INSERT INTO boost_campaign_claim(identity_hash,member_id,coupon_id,amount_cents) VALUES (?,?,?,1000)",identity,member,coupon);
        db.run("UPDATE boost_campaign_budget SET used_cents=used_cents+1000 WHERE budget_day=?",day);
        db.notify(member,"新人优惠券已到账","已验证微信身份，可在有效期内使用满100减10元优惠券。",null);
    }
}
