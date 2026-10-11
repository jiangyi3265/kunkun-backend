package com.ruoyi.boost;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import static com.ruoyi.boost.BoostDb.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real service proxies and isolated InnoDB. Barriers pause calls without changing query results.
 * Coupons are an isolated budget fixture, never a payment/channel or wallet transaction. */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes=BoostMessageCampaignBoundaryTest.Config.class)
@EnabledIfEnvironmentVariable(named="BOOST_TEST_DB_URL", matches=".+")
class BoostMessageCampaignBoundaryTest {
    @Configuration @EnableTransactionManagement static class Config {
        @Bean DataSource source(){return new BoostAbuseControlTest.Config().source();}
        @Bean PlatformTransactionManager tx(DataSource d){return new DataSourceTransactionManager(d);}
        @Bean PausingDb db(DataSource d){return new PausingDb(new JdbcTemplate(d));}
        @Bean BoostCampaign campaign(BoostDb d){return new BoostCampaign(d);}
        @Bean BoostAuth auth(BoostDb d){return new BoostAuth(d);}
        @Bean BoostMessages messages(BoostDb d){return new BoostMessages(d);}
    }
    static class PausingDb extends BoostDb {
        volatile Consumer<String> before=s->{},after=s->{};
        volatile boolean failMessageRead;
        PausingDb(JdbcTemplate jdbc){super(jdbc);}
        @Override public List<Map<String,Object>> list(String sql,Object... args){
            before.accept(sql);List<Map<String,Object>> rows=super.list(sql,args);after.accept(sql);return rows;
        }
        @Override public int run(String sql,Object... args){
            if(failMessageRead&&sql.startsWith("UPDATE boost_message SET is_read=TRUE"))
                throw new DataAccessResourceFailureException("Controlled isolated failure of message read update");
            return super.run(sql,args);
        }
    }
    @Autowired PausingDb db;
    @Autowired BoostAuth auth;
    @Autowired BoostMessages messages;
    @Autowired BoostCampaign campaign;
    long buyer,peer,other;
    ExecutorService pool;
    final List<CountDownLatch> gates=new ArrayList<>();
    @BeforeEach void setup(){
        pool=Executors.newFixedThreadPool(2);
        ReflectionTestUtils.setField(campaign,"dailyBudget",0L);
        ReflectionTestUtils.setField(campaign,"totalBudget",0L);
        buyer=member();peer=member();other=member();
        db.run("DELETE FROM boost_notification WHERE member_id IN (?,?,?)",buyer,peer,other);
    }
    long member(){return num((Map<String,Object>)auth.register(map("username","qa_mc_"+UUID.randomUUID().toString().replace("-", "").substring(0,18),"password","MessageCampaign2026!","nickname","隔离消息与活动测试")).get("user"),"id");}
    CountDownLatch gate(){CountDownLatch g=new CountDownLatch(1);gates.add(g);return g;}
    static void await(CountDownLatch latch){try{assertTrue(latch.await(12,TimeUnit.SECONDS),"Race gate timed out");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}
    <T> Future<T> start(String name,Callable<T> job){return pool.submit(()->{Thread.currentThread().setName(name);return job.call();});}
    long conversation(long a,long b){return messages.open(a,map("peerId",b));}
    void send(long sender,long conversation,String content){messages.send(sender,conversation,map("requestKey",UUID.randomUUID().toString(),"kind","TEXT","content",content));}
    @AfterEach void cleanup()throws Exception{
        gates.forEach(CountDownLatch::countDown);pool.shutdownNow();assertTrue(pool.awaitTermination(15,TimeUnit.SECONDS));
        db.before=s->{};db.after=s->{};db.failMessageRead=false;
        ReflectionTestUtils.setField(campaign,"dailyBudget",0L);ReflectionTestUtils.setField(campaign,"totalBudget",0L);
        db.run("DELETE FROM boost_message WHERE conversation_id IN (SELECT id FROM boost_conversation WHERE member_a IN (?,?,?) OR member_b IN (?,?,?))",buyer,peer,other,buyer,peer,other);
        db.run("DELETE FROM boost_conversation WHERE member_a IN (?,?,?) OR member_b IN (?,?,?)",buyer,peer,other,buyer,peer,other);
        long ownClaims=db.count("SELECT COALESCE(SUM(amount_cents),0) FROM boost_campaign_claim WHERE member_id IN (?,?,?)",buyer,peer,other);
        db.run("DELETE FROM boost_campaign_claim WHERE member_id IN (?,?,?)",buyer,peer,other);
        if(ownClaims>0){String day=LocalDate.now(ZoneOffset.UTC).toString();db.run("UPDATE boost_campaign_budget SET used_cents=used_cents-? WHERE budget_day=?",ownClaims,day);db.run("DELETE FROM boost_campaign_budget WHERE budget_day=? AND used_cents=0",day);}
        for(long id:new long[]{buyer,peer,other})if(id>0){
            assertEquals(0,db.count("SELECT balance_cents FROM boost_member WHERE id=?",id));
            assertEquals(0,db.count("SELECT COUNT(*) FROM boost_ledger WHERE member_id=?",id));
            assertEquals(0,db.count("SELECT COUNT(*) FROM boost_withdrawal WHERE member_id=?",id));
            db.run("DELETE FROM boost_notification WHERE member_id=?",id);db.run("DELETE FROM boost_coupon WHERE member_id=?",id);db.run("DELETE FROM boost_session WHERE member_id=?",id);db.run("DELETE FROM boost_member WHERE id=?",id);
        }
        assertEquals(0,db.count("SELECT COUNT(*) FROM boost_member WHERE id IN (?,?,?)",buyer,peer,other));
    }

    @Test void readAllFailureMustRollbackAlreadyUpdatedNotifications(){
        long c=conversation(buyer,peer);send(peer,c,"故障发生前仍未读的真实消息");db.notify(buyer,"仍未读通知","第二步故障后第一步应回滚",null);
        db.failMessageRead=true;
        assertThrows(DataAccessResourceFailureException.class,()->messages.readAll(buyer));
        db.failMessageRead=false;
        assertEquals(1,db.count("SELECT COUNT(*) FROM boost_notification WHERE member_id=? AND is_read=FALSE",buyer),"A failed all-read request must not partially commit the notification half");
        assertEquals(1,db.count("SELECT COUNT(*) FROM boost_message WHERE conversation_id=? AND is_read=FALSE",c));
    }

    @Test void successfulReadAllOnlyChangesCurrentMembersIncomingRecords(){
        long own=conversation(buyer,peer),unrelated=conversation(peer,other);
        send(peer,own,"当前用户收件");send(buyer,own,"当前用户发件应由对方确认");send(other,unrelated,"无关会话收件");
        db.notify(buyer,"当前通知","应被标为已读",null);db.notify(peer,"对方通知","不得修改",null);
        messages.readAll(buyer);
        assertEquals(0,db.count("SELECT COUNT(*) FROM boost_notification WHERE member_id=? AND is_read=FALSE",buyer));
        assertEquals(1,db.count("SELECT COUNT(*) FROM boost_notification WHERE member_id=? AND is_read=FALSE",peer));
        assertEquals(0,db.count("SELECT COUNT(*) FROM boost_message WHERE conversation_id=? AND sender_id=? AND is_read=FALSE",own,peer));
        assertEquals(1,db.count("SELECT COUNT(*) FROM boost_message WHERE conversation_id=? AND sender_id=? AND is_read=FALSE",own,buyer));
        assertEquals(1,db.count("SELECT COUNT(*) FROM boost_message WHERE conversation_id=? AND is_read=FALSE",unrelated));
    }

    @Test void existingWechatLoginAndIdempotentBindingCannotInvertCampaignMemberLocks()throws Exception{
        assertEquals(0,db.count("SELECT COUNT(*) FROM boost_campaign_claim"),"Use a fresh isolated test database without earlier campaign claims");
        String openid="qa-campaign-identity-"+buyer;db.run("UPDATE boost_member SET openid=? WHERE id=?",openid,buyer);
        ReflectionTestUtils.setField(campaign,"dailyBudget",1000L);ReflectionTestUtils.setField(campaign,"totalBudget",1000L);
        CountDownLatch mutexHeld=gate(),releaseLogin=gate(),bindingStarted=gate();
        db.after=sql->{if(Thread.currentThread().getName().equals("campaign-login-first")&&sql.contains("config_key='campaign:lock'")){mutexHeld.countDown();await(releaseLogin);}};
        db.before=sql->{if(Thread.currentThread().getName().equals("campaign-binding-second")&&sql.startsWith("SELECT openid,status FROM boost_member"))bindingStarted.countDown();};
        Future<Map<String,Object>> login=start("campaign-login-first",()->auth.wechat(openid,null));await(mutexHeld);
        Future<Map<String,Object>> binding=start("campaign-binding-second",()->auth.wechat(openid,buyer));await(bindingStarted);
        assertThrows(TimeoutException.class,()->binding.get(500,TimeUnit.MILLISECONDS),"Concurrent binding waits for the in-flight login locks");
        releaseLogin.countDown();
        Map<String,Object> loggedIn=assertDoesNotThrow(()->login.get(15,TimeUnit.SECONDS),"Normal login must not be an InnoDB deadlock victim");
        Map<String,Object> bound=assertDoesNotThrow(()->binding.get(15,TimeUnit.SECONDS),"Idempotent binding must not be an InnoDB deadlock victim");
        assertEquals(buyer,num(bound,"id"));
        MockHttpServletRequest request=new MockHttpServletRequest();request.addHeader("X-Boost-Token",str(loggedIn,"token"));assertEquals(buyer,auth.member(request));
        assertEquals(1,db.count("SELECT COUNT(*) FROM boost_campaign_claim WHERE member_id=?",buyer));
        assertEquals(1,db.count("SELECT COUNT(*) FROM boost_coupon WHERE member_id=?",buyer));
        assertEquals(1000,db.count("SELECT SUM(used_cents) FROM boost_campaign_budget"));
    }
}
