package com.ruoyi.boost;

import jakarta.annotation.PostConstruct;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;

@Component
public class BoostBootstrap {
    private final DataSource source;private final BoostDb db;private final BoostAuth auth;
    @Value("${boost.initialize-schema:false}") private boolean initialize;
    @Value("${boost.demo:false}") private boolean demo;
    @Value("${boost.demo-password:Demo2026!}") private String password;
    public BoostBootstrap(DataSource s,BoostDb d,BoostAuth a){source=s;db=d;auth=a;}
    @PostConstruct public void initialize(){
        if(initialize){ResourceDatabasePopulator p=new ResourceDatabasePopulator(new ClassPathResource("boost/schema.sql"));p.setSqlScriptEncoding("UTF-8");p.execute(source);
            ensureColumn("boost_member","openid","VARCHAR(100) UNIQUE");
            ensureColumn("boost_withdrawal","provider_bill","VARCHAR(100)");
            ensureColumn("boost_withdrawal","confirm_package","TEXT");
            ensureColumn("boost_order","provider_checked_at","TIMESTAMP NULL");
            ensureColumn("boost_order","snapshot_images","TEXT");
            // Preserve the best available cover for pre-migration orders from this point on.
            db.run("UPDATE boost_order SET snapshot_images=(SELECT images FROM boost_listing WHERE boost_listing.id=boost_order.listing_id) WHERE snapshot_images IS NULL");
            ensureColumn("boost_withdrawal","provider_checked_at","TIMESTAMP NULL");
            BoostMigrations.migrate(db);
        }
        if(demo)seed();
    }
    private void seed(){
        if(db.count("SELECT COUNT(*) FROM boost_member WHERE username='demo_buyer'")>0)return;
        long buyer=BoostDb.num((java.util.Map<String,Object>)auth.register(BoostDb.map("username","demo_buyer","password",password,"nickname","小橘同学")).get("user"),"id");
        long worker=BoostDb.num((java.util.Map<String,Object>)auth.register(BoostDb.map("username","demo_worker","password",password,"nickname","阿星电竞")).get("user"),"id");
        db.run("UPDATE boost_member SET certification='APPROVED',skill='王者荣耀排位上分，耐心沟通',avatar='hero' WHERE id=?",worker);
        db.run("UPDATE boost_member SET avatar='anime' WHERE id=?",buyer);
        String[] titles={"王者排位上分｜实力打手","和平精英冲分｜稳定在线","原神探索｜任务全清","巅峰赛陪练｜耐心教学","王牌进阶｜一起冲分","日常委托｜轻松探索"};
        String[] games={"王者荣耀","和平精英","原神"};int[] prices={2500,3500,1800,3900,8800,1800};
        for(int i=0;i<titles.length;i++)db.run("INSERT INTO boost_listing(owner_id,kind,title,game,zone,platform,current_rank,target_rank,duration_hours,price_cents,description,images,status) VALUES (?,'SERVICE',?,?,?,'安卓',?,?,24,?,?,'[]','ACTIVE')",worker,titles[i],games[i%3],i%3==2?"官方服":"微信区",i%3==0?"星耀 III":"当前进度",i%3==0?"星耀 II":"约定任务完成",prices[i],"接单前确认具体需求，每阶段同步进度截图。此为固定套餐价格，时间可提前沟通。全程文明交流，支持平台售后。");
        db.run("INSERT INTO boost_listing(owner_id,kind,title,game,zone,platform,current_rank,target_rank,duration_hours,price_cents,description,images,status) VALUES (?,'REQUEST','王者荣耀，星耀冲王者','王者荣耀','微信区','安卓','星耀 III','最强王者',24,12000,'今晚八点后可开打，请及时同步进度。','[]','ACTIVE')",buyer);
        db.run("INSERT INTO boost_listing(owner_id,kind,title,game,zone,platform,current_rank,target_rank,duration_hours,price_cents,description,images,status) VALUES (?,'REQUEST','原神指定区域探索','原神','官方服','全平台','探索 30%','探索 80%',48,6000,'指定区域探索，具体范围请在接单前沟通。','[]','ACTIVE')",buyer);
    }
    private void ensureColumn(String table,String column,String type){
        db.template().execute((org.springframework.jdbc.core.ConnectionCallback<Void>)c->{
            try(java.sql.ResultSet r=c.getMetaData().getColumns(c.getCatalog(),null,table,column)){
                if(!r.next())try(java.sql.Statement s=c.createStatement()){s.execute("ALTER TABLE "+table+" ADD COLUMN "+column+" "+type);}
            }return null;
        });
    }
}
