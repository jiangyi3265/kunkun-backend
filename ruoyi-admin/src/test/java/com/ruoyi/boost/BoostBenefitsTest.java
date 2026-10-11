package com.ruoyi.boost;
import java.util.*;
import java.time.LocalDateTime;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import static org.junit.jupiter.api.Assertions.*;
import static com.ruoyi.boost.BoostDb.*;
@ExtendWith(SpringExtension.class) @ContextConfiguration(classes=BoostBenefitsTest.Config.class)
class BoostBenefitsTest {
 @Configuration @EnableTransactionManagement static class Config {
  @Bean DataSource dataSource(){JdbcDataSource d=new JdbcDataSource();d.setURL("jdbc:h2:mem:benefits;MODE=MySQL;DB_CLOSE_DELAY=-1");BoostRewardsTest.Config.initialize(d);return d;}
  @Bean JdbcTemplate jdbc(DataSource d){return new JdbcTemplate(d);}
  @Bean PlatformTransactionManager tx(DataSource d){return new DataSourceTransactionManager(d);}
  @Bean BoostDb db(JdbcTemplate j){return new BoostDb(j);}
  @Bean BoostAuth auth(BoostDb d){return new BoostAuth(d);}
  @Bean BoostBenefits benefits(BoostDb d){return new BoostBenefits(d);}
  @Bean BoostPlayerMarket player(BoostDb d){return new BoostPlayerMarket(d);}
 }
 @Autowired BoostDb db;@Autowired BoostAuth auth;@Autowired BoostBenefits benefits;@Autowired BoostPlayerMarket player;long member;
 @BeforeEach void prepare(){member=num((Map<String,Object>)auth.register(map("username","bn_"+UUID.randomUUID().toString().replace("-","").substring(0,12),"password","BenefitFixture123!","nickname","成长测试")).get("user"),"id");db.run("UPDATE boost_config SET config_value='' WHERE config_key='player:growth-thresholds'");}
 Map<String,Object> body(String action,long revision){return map("action",action,"expectedRevision",revision,"requestKey",UUID.randomUUID().toString(),"note","人工测试调整");}
 void rules(){benefits.rules(1,map("thresholds","0,100,300","expectedThresholds","","note","配置测试等级"));}
 @Test void membershipExpiryCancellationAndNoCertificationBypass(){Map<String,Object>b=body("membership",0);b.put("membershipType","MEMBER");b.put("expiresAt",LocalDateTime.now().plusDays(1).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")));benefits.update(1,member,b);benefits.update(1,member,b);assertEquals(true,player.profile(member).get("membershipActive"));assertEquals(1,db.count("SELECT COUNT(*) FROM boost_benefit_action WHERE member_id=?",member));assertThrows(BoostException.class,()->player.requirePublisher(member));db.run("UPDATE boost_player_profile SET membership_expires_at=? WHERE member_id=?",java.sql.Timestamp.valueOf(LocalDateTime.now().minusSeconds(1)),member);assertEquals(false,player.profile(member).get("membershipActive"));Map<String,Object>cancel=body("membership",1);cancel.put("membershipType","FREE");benefits.update(1,member,cancel);assertNull(player.profile(member).get("membershipExpiresAt"));assertEquals("FREE",str(player.profile(member),"membershipType"));}
 @Test void growthIdempotencyStaleWriteAndRuleProgress(){rules();Map<String,Object>b=body("growth",0);b.put("delta",150);benefits.update(1,member,b);benefits.update(1,member,b);assertEquals(150,num(player.profile(member),"growthPoints"));assertEquals(2,num(player.profile(member),"level"));assertEquals(25,num(player.profile(member),"growthProgress"));assertEquals(1,db.count("SELECT COUNT(*) FROM boost_growth_event WHERE member_id=?",member));Map<String,Object>stale=body("growth",0);stale.put("delta",10);assertEquals(409,assertThrows(BoostException.class,()->benefits.update(1,member,stale)).code);b.put("delta",151);assertEquals(409,assertThrows(BoostException.class,()->benefits.update(1,member,b)).code);assertEquals(150,num(player.profile(member),"growthPoints"));Map<String,Object>max=body("growth",1);max.put("delta",150);benefits.update(1,member,max);assertEquals(100,num(player.profile(member),"growthProgress"));assertEquals(0,db.count("SELECT COUNT(*) FROM boost_ledger WHERE member_id=?",member));}
 @Test void malformedRulesNegativeGrowthAndSuspensionCannotChangeData(){for(String bad:List.of("100,200","0,0","0,100,99","0,abc","0"))assertThrows(BoostException.class,()->benefits.rules(1,map("thresholds",bad,"expectedThresholds","","note","无效阈值")));Map<String,Object>b=body("growth",0);b.put("delta",1);assertThrows(BoostException.class,()->benefits.update(1,member,b));rules();b.put("delta",-1);assertThrows(BoostException.class,()->benefits.update(1,member,b));assertEquals(0,db.count("SELECT COUNT(*) FROM boost_growth_event WHERE member_id=?",member));db.run("UPDATE boost_member SET status='BLOCKED' WHERE id=?",member);b.put("delta",10);assertThrows(BoostException.class,()->benefits.update(1,member,b));assertEquals(0,db.count("SELECT COUNT(*) FROM boost_benefit_action WHERE member_id=?",member));assertEquals(409,assertThrows(BoostException.class,()->benefits.rules(1,map("thresholds","0,200","expectedThresholds","","note","旧版本覆盖"))).code);}
}
