package com.ruoyi.boost;

import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import static com.ruoyi.boost.BoostDb.*;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(SpringExtension.class) @ContextConfiguration(classes=BoostAbuseControlTest.Config.class)
class BoostAbuseControlTest {
 @Configuration @EnableTransactionManagement static class Config {
  @Bean DataSource source(){
   String url=System.getenv("BOOST_TEST_DB_URL");DataSource d;
   if(url!=null){if(!url.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost)(:[0-9]+)?/boost_verify_[0-9]+\\?.*"))throw new IllegalArgumentException("Only an isolated local boost_verify database is allowed");d=new DriverManagerDataSource(url,System.getenv("BOOST_TEST_DB_USER"),System.getenv("BOOST_TEST_DB_PASSWORD"));}
   else{JdbcDataSource h=new JdbcDataSource();h.setURL("jdbc:h2:mem:abuse;MODE=MySQL;DB_CLOSE_DELAY=-1");d=h;}
   new ResourceDatabasePopulator(new ClassPathResource("boost/schema.sql")).execute(d);BoostMigrations.migrate(new BoostDb(new JdbcTemplate(d)));return d;
  }
  @Bean BoostDb db(DataSource d){return new BoostDb(new JdbcTemplate(d));}
  @Bean PlatformTransactionManager tx(DataSource d){return new DataSourceTransactionManager(d);}
  @Bean BoostRateLimits limits(BoostDb d){return new BoostRateLimits(d);}
  @Bean BoostCampaign campaign(BoostDb d){return new BoostCampaign(d);}
  @Bean BoostAuth auth(BoostDb d){return new BoostAuth(d);}
 }
 @Autowired BoostDb db;@Autowired BoostRateLimits limits;@Autowired BoostCampaign campaign;@Autowired BoostAuth auth;@Autowired PlatformTransactionManager tx;
 @Test void failedOuterTransactionsAndNewServiceInstancesCannotResetRateLimit(){
  String key="qa-rate-"+UUID.randomUUID();
  try{
   new TransactionTemplate(tx).execute(s->{limits.hit(key,2,60);s.setRollbackOnly();return null;});
   new TransactionTemplate(tx).execute(s->{new BoostRateLimits(db).hit(key,2,60);return null;});
   assertEquals(429,assertThrows(BoostException.class,()->limits.hit(key,2,60)).code);
   db.run("UPDATE boost_rate_limit SET resets_at=? WHERE bucket_key=?",java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(1)),BoostIdempotency.digest(map("key",key)));
   assertDoesNotThrow(()->limits.hit(key,2,60));
  }finally{limits.clear(key);}
 }
 @Test @EnabledIfEnvironmentVariable(named="BOOST_TEST_DB_URL",matches=".+")
 void mysqlExistingSnapshotsCannotOverspendCampaignBudget()throws Exception{
  ReflectionTestUtils.setField(campaign,"dailyBudget",2000L);ReflectionTestUtils.setField(campaign,"totalBudget",3000L);
  assertEquals(0,db.count("SELECT COUNT(*) FROM boost_campaign_claim"),"Use a fresh isolated rehearsal database");
  List<Long> members=new ArrayList<>();ExecutorService pool=Executors.newFixedThreadPool(8);CyclicBarrier barrier=new CyclicBarrier(8);
  try{
   for(int i=0;i<8;i++)members.add(num((Map<String,Object>)auth.register(map("username","qa_budget_"+UUID.randomUUID().toString().replace("-","").substring(0,18),"password","BudgetTest2026!","nickname","隔离预算并发测试")).get("user"),"id"));
   List<Future<?>> jobs=new ArrayList<>();
   for(long member:members)jobs.add(pool.submit(()->{TransactionTemplate t=new TransactionTemplate(tx);t.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);t.execute(s->{db.count("SELECT COUNT(*) FROM boost_campaign_claim");try{barrier.await(10,TimeUnit.SECONDS);}catch(Exception e){throw new RuntimeException(e);}campaign.claim(member,"qa-budget-openid-"+member);return null;});}));
   for(Future<?> job:jobs)job.get(30,TimeUnit.SECONDS);
   assertEquals(2,db.count("SELECT COUNT(*) FROM boost_campaign_claim"));assertEquals(2000,db.count("SELECT SUM(used_cents) FROM boost_campaign_budget"));
  }finally{
   pool.shutdownNow();for(long id:members){db.run("DELETE FROM boost_campaign_claim WHERE member_id=?",id);db.run("DELETE FROM boost_notification WHERE member_id=?",id);db.run("DELETE FROM boost_coupon WHERE member_id=?",id);db.run("DELETE FROM boost_session WHERE member_id=?",id);db.run("DELETE FROM boost_member WHERE id=?",id);}db.run("DELETE FROM boost_campaign_budget");
  }
 }
}
