package com.ruoyi.boost;
import java.util.*;import java.util.concurrent.*;import javax.sql.DataSource;
import org.junit.jupiter.api.*;import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.*;import org.springframework.jdbc.datasource.DriverManagerDataSource;import org.springframework.test.context.ContextConfiguration;
import static org.junit.jupiter.api.Assertions.*;import static com.ruoyi.boost.BoostDb.*;
@EnabledIfEnvironmentVariable(named="BOOST_REWARDS_TEST_DB_URL",matches=".+")
@ContextConfiguration(classes=BoostBenefitsMySqlTest.MySqlConfig.class,inheritLocations=false)
class BoostBenefitsMySqlTest extends BoostBenefitsTest {
 @Configuration static class MySqlConfig extends BoostBenefitsTest.Config {
  @Override @Bean DataSource dataSource(){String url=System.getenv("BOOST_REWARDS_TEST_DB_URL");if(url==null||!url.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost)(:[0-9]+)?/boost_verify_[0-9]+\\?.*"))throw new IllegalArgumentException("Owned localhost database required");DataSource d=new DriverManagerDataSource(url,System.getenv("BOOST_REWARDS_TEST_DB_USER"),System.getenv("BOOST_REWARDS_TEST_DB_PASSWORD"));BoostRewardsTest.Config.initialize(d);return d;}
 }
 @Test void concurrentRetryCreatesOneGrowthEntry()throws Exception{rules();Map<String,Object>b=body("growth",0);b.put("delta",100);CountDownLatch start=new CountDownLatch(1);ExecutorService pool=Executors.newFixedThreadPool(2);try{List<Future<?>>f=new ArrayList<>();for(int i=0;i<2;i++)f.add(pool.submit(()->{try{start.await();}catch(InterruptedException e){throw new AssertionError(e);}return benefits.update(1,member,b);}));start.countDown();for(Future<?>x:f)x.get(15,TimeUnit.SECONDS);assertEquals(100,num(player.profile(member),"growthPoints"));assertEquals(1,db.count("SELECT COUNT(*) FROM boost_growth_event WHERE member_id=?",member));assertEquals(1,db.count("SELECT COUNT(*) FROM boost_benefit_action WHERE member_id=?",member));}finally{pool.shutdownNow();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));}}
}
