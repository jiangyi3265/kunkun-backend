package com.ruoyi.boost;
import java.sql.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
/** Offline command; does not start HTTP, jobs, Redis or demo data. */
public final class BoostMigrationCommand {
    private static String required(String key){String v=System.getenv(key);if(v==null||v.isBlank())throw new IllegalStateException("Missing "+key);return v;}
    public static void main(String[] args)throws Exception{
        try(Connection c=DriverManager.getConnection(required("BOOST_DB_URL"),required("BOOST_DB_USER"),required("BOOST_DB_PASSWORD"))){
            String database=c.getCatalog();if(!database.equals(required("BOOST_MIGRATION_DATABASE")))throw new IllegalStateException("Target database does not match explicit migration acknowledgement");
            SingleConnectionDataSource source=new SingleConnectionDataSource(c,true);BoostDb db=new BoostDb(new JdbcTemplate(source));
            if(db.count("SELECT GET_LOCK(?,0)","boost-schema:"+database)!=1)throw new IllegalStateException("Another migration is running");
            try{ResourceDatabasePopulator p=new ResourceDatabasePopulator(new ClassPathResource("boost/schema.sql"));p.setSqlScriptEncoding("UTF-8");p.execute(source);BoostMigrations.migrate(db);System.out.println("Migration verified: "+BoostMigrations.VERSION);}
            finally{db.count("SELECT RELEASE_LOCK(?)","boost-schema:"+database);}
        }
    }
}
