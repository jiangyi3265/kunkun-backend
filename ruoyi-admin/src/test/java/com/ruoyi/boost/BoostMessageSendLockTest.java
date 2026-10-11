package com.ruoyi.boost;

import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import static com.ruoyi.boost.BoostDb.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual proxies and InnoDB. Before-only INSERT barrier proves the former FK lock
 * upgrade cycle; final regression starts both authenticated sends together without
 * forcing a second insert through a lock legitimately held by the first sender. */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes=BoostMessageSendLockTest.Config.class)
@EnabledIfEnvironmentVariable(named="BOOST_TEST_DB_URL",matches=".+")
class BoostMessageSendLockTest {
    @Configuration @EnableTransactionManagement static class Config {
        @Bean DataSource source(){return new BoostAbuseControlTest.Config().source();}
        @Bean PlatformTransactionManager tx(DataSource d){return new DataSourceTransactionManager(d);}
        @Bean ControlledDb db(DataSource d){return new ControlledDb(new JdbcTemplate(d));}
        @Bean BoostAuth auth(BoostDb d){return new BoostAuth(d);}
        @Bean BoostMessages messages(BoostDb d){return new BoostMessages(d);}
    }
    static class ControlledDb extends BoostDb {
        volatile CyclicBarrier inserted;
        ControlledDb(JdbcTemplate j){super(j);}
        @Override public int run(String sql,Object...args){
            int result=super.run(sql,args);
            if(sql.startsWith("INSERT INTO boost_message")&&inserted!=null){
                try{inserted.await(12,TimeUnit.SECONDS);}catch(Exception e){throw new AssertionError("Before-only actual INSERT scheduling barrier failed",e);}
            }
            return result;
        }
    }
    @Autowired ControlledDb db;@Autowired BoostAuth auth;@Autowired BoostMessages messages;
    long a,b,outsider,conversation;String ta,tb,tc;ExecutorService pool;
    Map<String,Object> account(){return auth.register(map("username","qa_ms_"+UUID.randomUUID().toString().replace("-", "").substring(0,18),"password","MessageBoundary2026!","nickname","隔离聊天并发"));}
    @BeforeEach void setup(){
        pool=Executors.newFixedThreadPool(2);var first=account();var second=account();var third=account();
        a=num((Map<String,Object>)first.get("user"),"id");b=num((Map<String,Object>)second.get("user"),"id");outsider=num((Map<String,Object>)third.get("user"),"id");
        ta=str(first,"token");tb=str(second,"token");tc=str(third,"token");conversation=messages.open(a,map("peerId",b));db.inserted=null;
    }
    long admitted(String token){var r=new MockHttpServletRequest();r.addHeader("X-Boost-Token",token);return auth.member(r);}
    Map<String,Object> payload(String content,String key){return map("kind","TEXT","content",content,"requestKey",key);}
    @Test void reciprocalAuthenticatedSendsBothPersistExactlyOnce()throws Exception{
        assertEquals(a,admitted(ta));assertEquals(b,admitted(tb));
        boolean beforeProbe="true".equals(System.getenv("BOOST_PROBE_SEND_INSERT_BARRIER"));
        int rounds=beforeProbe?1:6;
        for(int n=0;n<rounds;n++){
            if(beforeProbe)db.inserted=new CyclicBarrier(2);
            var start=new CountDownLatch(1);String ka=UUID.randomUUID().toString(),kb=UUID.randomUUID().toString();
            Future<?> fa=pool.submit(()->{assertTrue(start.await(10,TimeUnit.SECONDS));messages.send(a,conversation,payload("甲正常发送",ka));return null;});
            Future<?> fb=pool.submit(()->{assertTrue(start.await(10,TimeUnit.SECONDS));messages.send(b,conversation,payload("乙正常发送",kb));return null;});
            start.countDown();assertAll(
                ()->assertDoesNotThrow(()->fa.get(20,TimeUnit.SECONDS),"Normal sender A must not lose its committed message to an InnoDB deadlock"),
                ()->assertDoesNotThrow(()->fb.get(20,TimeUnit.SECONDS),"Normal sender B must not lose its committed message to an InnoDB deadlock"));
            assertEquals(1,db.count("SELECT COUNT(*) FROM boost_message WHERE conversation_id=? AND sender_id=? AND request_key=?",conversation,a,ka));
            assertEquals(1,db.count("SELECT COUNT(*) FROM boost_message WHERE conversation_id=? AND sender_id=? AND request_key=?",conversation,b,kb));
        }
        assertEquals(rounds*2,db.count("SELECT COUNT(*) FROM boost_message WHERE conversation_id=?",conversation));
    }
    @Test void identicalSendRetryRemainsOnceAndConflictingPayloadIsRejected(){
        String key=UUID.randomUUID().toString();var body=payload("正常幂等消息",key);messages.send(admitted(ta),conversation,body);messages.send(admitted(ta),conversation,body);
        assertEquals(1,db.count("SELECT COUNT(*) FROM boost_message WHERE conversation_id=?",conversation));
        BoostException conflict=assertThrows(BoostException.class,()->messages.send(a,conversation,payload("不同消息",key)));assertEquals(409,conflict.code);
        assertEquals(1,db.count("SELECT COUNT(*) FROM boost_message WHERE conversation_id=?",conversation));
    }
    @Test void separateConversationsCannotDeadlockOnAnEmptySenderRequestIndex()throws Exception{
        long other=messages.open(b,map("peerId",outsider));assertEquals(a,admitted(ta));assertEquals(outsider,admitted(tc));
        try{
            boolean beforeProbe="true".equals(System.getenv("BOOST_PROBE_SEND_INSERT_BARRIER"));int rounds=beforeProbe?1:6;
            for(int n=0;n<rounds;n++){
                if(beforeProbe)db.inserted=new CyclicBarrier(2);
                var start=new CountDownLatch(1);String ka=UUID.randomUUID().toString(),kc=UUID.randomUUID().toString();
                Future<?> fa=pool.submit(()->{assertTrue(start.await(10,TimeUnit.SECONDS));messages.send(a,conversation,payload("会话甲正常发送",ka));return null;});
                Future<?> fc=pool.submit(()->{assertTrue(start.await(10,TimeUnit.SECONDS));messages.send(outsider,other,payload("会话乙正常发送",kc));return null;});
                start.countDown();assertAll(
                    ()->assertDoesNotThrow(()->fa.get(20,TimeUnit.SECONDS),"Independent sender A must not lose its message to a unique-index gap deadlock"),
                    ()->assertDoesNotThrow(()->fc.get(20,TimeUnit.SECONDS),"Independent sender C must not lose its message to a unique-index gap deadlock"));
                assertEquals(1,db.count("SELECT COUNT(*) FROM boost_message WHERE conversation_id=? AND sender_id=? AND request_key=?",conversation,a,ka));
                assertEquals(1,db.count("SELECT COUNT(*) FROM boost_message WHERE conversation_id=? AND sender_id=? AND request_key=?",other,outsider,kc));
            }
            assertEquals(rounds,db.count("SELECT COUNT(*) FROM boost_message WHERE conversation_id=?",conversation));
            assertEquals(rounds,db.count("SELECT COUNT(*) FROM boost_message WHERE conversation_id=?",other));
        }finally{
            if(db.inserted!=null)db.inserted.reset();pool.shutdownNow();assertTrue(pool.awaitTermination(25,TimeUnit.SECONDS));db.inserted=null;
            db.run("DELETE FROM boost_message WHERE conversation_id=?",other);db.run("DELETE FROM boost_conversation WHERE id=?",other);
        }
    }
    @Test void thirdAuthenticatedMemberCannotWriteTheConversation(){
        assertEquals(outsider,admitted(tc));
        BoostException denied=assertThrows(BoostException.class,()->messages.send(outsider,conversation,payload("越权写入禁止",UUID.randomUUID().toString())));assertEquals(403,denied.code);
        assertEquals(0,db.count("SELECT COUNT(*) FROM boost_message WHERE conversation_id=?",conversation));
    }
    @AfterEach void cleanup()throws Exception{
        if(db.inserted!=null)db.inserted.reset();pool.shutdownNow();assertTrue(pool.awaitTermination(25,TimeUnit.SECONDS));db.inserted=null;
        assertEquals(0,db.count("SELECT COALESCE(SUM(balance_cents),0) FROM boost_member WHERE id IN (?,?,?)",a,b,outsider));
        assertEquals(0,db.count("SELECT COUNT(*) FROM boost_ledger"));assertEquals(0,db.count("SELECT COUNT(*) FROM boost_withdrawal"));
        db.run("DELETE FROM boost_message WHERE conversation_id=?",conversation);db.run("DELETE FROM boost_conversation WHERE id=?",conversation);
        for(long id:new long[]{a,b,outsider}){db.run("DELETE FROM boost_notification WHERE member_id=?",id);db.run("DELETE FROM boost_session WHERE member_id=?",id);db.run("DELETE FROM boost_member WHERE id=?",id);}
    }
}
