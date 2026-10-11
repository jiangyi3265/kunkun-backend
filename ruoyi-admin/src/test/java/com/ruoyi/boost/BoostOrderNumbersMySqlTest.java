package com.ruoyi.boost;

import javax.sql.DataSource;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Repeat the allocator's transaction/concurrency tests on actual isolated InnoDB. */
@EnabledIfEnvironmentVariable(named="BOOST_PLAYER_TEST_DB_URL",matches=".+")
class BoostOrderNumbersMySqlTest extends BoostOrderNumbersTest {
    @Override DataSource source(){
        String url=System.getenv("BOOST_PLAYER_TEST_DB_URL");
        if(url==null||!url.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost)(:[0-9]+)?/boost_verify_[0-9]+\\?.*"))
            throw new IllegalArgumentException("Only an isolated local order-number test database is allowed");
        return new DriverManagerDataSource(url,System.getenv("BOOST_PLAYER_TEST_DB_USER"),System.getenv("BOOST_PLAYER_TEST_DB_PASSWORD"));
    }
}
