package com.ruoyi.boost;

import java.util.UUID;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import static com.ruoyi.boost.BoostDb.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real local transaction rollback, with no external WeChat network request. */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes=BoostUserNumberCapacityTest.Config.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class BoostUserNumberCapacityTest {
    @Configuration @EnableTransactionManagement static class Config {
        @Bean DataSource dataSource(){JdbcDataSource s=new JdbcDataSource();s.setURL("jdbc:h2:mem:usernumber"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");new ResourceDatabasePopulator(new ClassPathResource("boost/schema.sql")).execute(s);BoostMigrations.migrate(new BoostDb(new JdbcTemplate(s)));return s;}
        @Bean JdbcTemplate jdbc(DataSource s){return new JdbcTemplate(s);}
        @Bean BoostDb db(JdbcTemplate j){return new BoostDb(j);}
        @Bean BoostAuth auth(BoostDb d){return new BoostAuth(d);}
        @Bean PlatformTransactionManager tx(DataSource s){return new DataSourceTransactionManager(s);}
    }
    @Autowired BoostDb db;
    @Autowired BoostAuth auth;

    private void exhaust(){db.run("ALTER TABLE boost_member ALTER COLUMN id RESTART WITH 10000");}
    private void assertEmpty(){for(String table:new String[]{"boost_member","boost_session","boost_notification","boost_coupon"})assertEquals(0,db.count("SELECT COUNT(*) FROM "+table),table);}
    @Test void registrationAtCapacityLeavesNoAccountSessionOrWelcomeRecords(){
        exhaust();assertThrows(BoostException.class,()->auth.register(map("username","capacity_user","password","CapacityTest123!","nickname","容量测试")));assertEmpty();
    }
    @Test void newWechatMemberAtCapacityAlsoRollsBack(){
        exhaust();assertThrows(BoostException.class,()->auth.wechat("isolated-capacity-openid",null));assertEmpty();
    }
}
