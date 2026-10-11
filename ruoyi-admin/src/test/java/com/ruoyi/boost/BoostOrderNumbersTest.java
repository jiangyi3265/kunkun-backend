package com.ruoyi.boost;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

class BoostOrderNumbersTest {
    BoostDb db;DataSource source;TransactionTemplate tx;
    final Clock beforeMidnight=Clock.fixed(Instant.parse("2099-12-30T15:59:59Z"),ZoneOffset.UTC);
    final Clock midnight=Clock.fixed(Instant.parse("2099-12-30T16:00:00Z"),ZoneId.of("America/New_York"));
    DataSource source(){JdbcDataSource d=new JdbcDataSource();d.setURL("jdbc:h2:mem:order_serial_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");return d;}
    @BeforeEach void setup(){
        source=source();db=new BoostDb(new JdbcTemplate(source));tx=new TransactionTemplate(new DataSourceTransactionManager(source));tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        db.run("CREATE TABLE IF NOT EXISTS boost_config(config_key VARCHAR(60) PRIMARY KEY,config_value TEXT NOT NULL)");
        clearFixture();
    }
    @AfterEach void clearFixture(){for(String day:List.of("G20991230","G20991231"))db.run("DELETE FROM boost_config WHERE config_key=?","sequence:order:"+day);}
    String next(Clock c){return tx.execute(s->BoostOrderNumbers.next(db,c));}
    @Test void chinaDateRolloverUsesIndependentFourDigitSerials(){
        assertEquals("G209912300001",next(beforeMidnight));assertEquals("G209912300002",next(beforeMidnight));
        assertEquals("G209912310001",next(midnight));assertEquals("G209912310002",next(midnight));
    }
    @Test void committedSequenceSurvivesANewDbAdapter(){
        assertEquals("G209912300001",next(beforeMidnight));db=new BoostDb(new JdbcTemplate(source));assertEquals("G209912300002",next(beforeMidnight));
    }
    @Test void failedOrderTransactionDoesNotConsumeANumber(){
        assertThrows(IllegalArgumentException.class,()->tx.execute(s->{assertEquals("G209912300001",BoostOrderNumbers.next(db,beforeMidnight));throw new IllegalArgumentException("Controlled order failure");}));
        assertEquals("G209912300001",next(beforeMidnight));
    }
    @Test void fourDigitCapacityNeverWrapsOrCreatesFiveDigitNumbers(){
        next(beforeMidnight);db.run("UPDATE boost_config SET config_value='9998' WHERE config_key='sequence:order:G20991230'");
        assertEquals("G209912309999",next(beforeMidnight));BoostException error=assertThrows(BoostException.class,()->next(beforeMidnight));assertTrue(error.getMessage().contains("编号已用完"));
        assertEquals(9999,db.count("SELECT config_value FROM boost_config WHERE config_key='sequence:order:G20991230'"));assertEquals("G209912310001",next(midnight));
    }
    @Test void allocatorCannotEscapeOrderTransaction(){assertThrows(IllegalStateException.class,()->BoostOrderNumbers.next(db,beforeMidnight));}
    @Test void concurrentFirstUseAllocatesExactlyOneDistinctNumberPerCommit()throws Exception{
        int count=24;ExecutorService pool=Executors.newFixedThreadPool(8);CountDownLatch gate=new CountDownLatch(1);List<Future<String>> calls=new ArrayList<>();
        try{
            for(int i=0;i<count;i++)calls.add(pool.submit(()->{assertTrue(gate.await(10,TimeUnit.SECONDS));return next(beforeMidnight);}));gate.countDown();
            Set<String> actual=new HashSet<>();for(Future<String> call:calls)assertTrue(actual.add(call.get(30,TimeUnit.SECONDS)),"Duplicate committed number");
            Set<String> expected=new HashSet<>();for(int i=1;i<=count;i++)expected.add(String.format(Locale.ROOT,"G20991230%04d",i));assertEquals(expected,actual);assertEquals("G209912300025",next(beforeMidnight));
        }finally{gate.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));}
    }
}
