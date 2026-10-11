package com.ruoyi.boost;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import java.util.regex.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

/** Only additive boost_* DDL; interrupted runs resume without replaying destructive SQL. */
public final class BoostMigrations {
    public static final String VERSION="V009__wallet_deposit_display";
    private static final String[] VERSIONS={"V002__commercial_hardening","V003__player_items","V004__player_public_profiles","V005__free_points_rewards","V006__player_benefits","V007__unit_items_content","V008__shop_disclosure_phone",VERSION};
    private BoostMigrations(){}
    public static void verifyApplied(BoostDb db) throws Exception {
        for(String version:VERSIONS){
        String sql=StreamUtils.copyToString(new ClassPathResource("boost/migrations/"+version+".sql").getInputStream(),StandardCharsets.UTF_8);
        String checksum=Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(sql.getBytes(StandardCharsets.UTF_8)));
        List<Map<String,Object>> applied=db.list("SELECT config_value FROM boost_config WHERE config_key=?","schema:"+version);
        if(applied.size()!=1||!checksum.equals(BoostDb.str(applied.get(0),"configValue")))throw new IllegalStateException("Missing or changed production migration: "+version);
        }
    }
    public static void migrate(BoostDb db) {
        for(String version:VERSIONS)migrateVersion(db,version);
    }
    private static void migrateVersion(BoostDb db,String version) {
        try {
            String sql=StreamUtils.copyToString(new ClassPathResource("boost/migrations/"+version+".sql").getInputStream(),StandardCharsets.UTF_8);
            String checksum=Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(sql.getBytes(StandardCharsets.UTF_8)));
            List<Map<String,Object>> applied=db.list("SELECT config_value FROM boost_config WHERE config_key=?","schema:"+version);
            if(!applied.isEmpty()){
                if(!checksum.equals(BoostDb.str(applied.get(0),"configValue")))throw new IllegalStateException("Applied migration checksum changed: "+version);
                return;
            }
            for(String statement:sql.replaceAll("(?m)^--.*$","").split(";")){
                String s=statement.trim();if(s.isEmpty())continue;
                Matcher col=Pattern.compile("ALTER TABLE (boost_\\w+) ADD COLUMN (\\w+).*",Pattern.DOTALL).matcher(s);
                Matcher idx=Pattern.compile("CREATE (?:UNIQUE )?INDEX (\\w+) ON (boost_\\w+).*",Pattern.DOTALL).matcher(s);
                if(col.matches()&&exists(db,col.group(1),col.group(2),false))continue;
                if(idx.matches()&&exists(db,idx.group(2),idx.group(1),true))continue;
                if(!s.matches("(?s)(ALTER TABLE boost_.*|CREATE (UNIQUE )?INDEX .* ON boost_.*|CREATE TABLE IF NOT EXISTS boost_.*|INSERT INTO boost_config.*|UPDATE boost_order SET expires_at=.*)"))throw new IllegalStateException("Unexpected migration statement");
                db.template().execute(s);
            }
            db.run("INSERT INTO boost_config(config_key,config_value) VALUES (?,?)","schema:"+version,checksum);
        }catch(Exception e){throw new IllegalStateException("Boost migration failed; keep application offline and resume the same migration",e);}
    }
    private static boolean exists(BoostDb db,String table,String name,boolean index){
        return db.template().execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>)c->{
            for(String t:new String[]{table,table.toUpperCase(Locale.ROOT)}){
                try(ResultSet r=index?c.getMetaData().getIndexInfo(c.getCatalog(),null,t,false,false):c.getMetaData().getColumns(c.getCatalog(),null,t,null)){
                    while(r.next())if(name.equalsIgnoreCase(r.getString(index?"INDEX_NAME":"COLUMN_NAME")))return true;
                }
            }return false;
        });
    }
}
