package com.ruoyi.boost;

import java.util.*;
import org.springframework.stereotype.Component;
import com.ruoyi.common.utils.SecurityUtils;

@Component("boostAccess")
public class BoostAccess {
    @org.springframework.beans.factory.annotation.Autowired private BoostDb db;
    public boolean can(String permission){
        try{
            // Cached LoginUser permissions may survive a role edit. They may restrict a session,
            // but cannot authorize an action after current database permissions are withdrawn.
            if(!SecurityUtils.hasPermi(permission))return false;
            long id=SecurityUtils.getUserId();
            if(db.count("SELECT COUNT(*) FROM sys_user WHERE user_id=? AND status='0' AND del_flag='0'",id)!=1)return false;
            if(id==1)return true;
            for(Map<String,Object> row:db.list("SELECT DISTINCT m.perms FROM sys_menu m JOIN sys_role_menu rm ON rm.menu_id=m.menu_id JOIN sys_user_role ur ON ur.role_id=rm.role_id JOIN sys_role r ON r.role_id=ur.role_id WHERE ur.user_id=? AND m.status='0' AND r.status='0' AND r.del_flag='0'",id))
                for(String granted:BoostDb.str(row,"perms").split(","))
                    if(org.springframework.util.PatternMatchUtils.simpleMatch(granted.trim(),permission))return true;
            return false;
        }catch(Exception ignored){return false;}
    }
    public boolean list(String type){return Arrays.asList("listings","members","shops","orders","aftersales","withdrawals","audit").contains(type)&&can("boost:"+type+":read");}
    public boolean moderate(String type,Map<String,Object> body){
        if(!Arrays.asList("listings","members","shops").contains(type))return false;
        String action=BoostDb.str(body,"action");
        return can("boost:"+type+":"+("members".equals(type)&&Arrays.asList("block","unblock").contains(action)?"status":"review"));
    }
    public boolean media(String purpose){
        if("SHOP_CERTIFICATION".equals(purpose))return can("boost:shops:review");
        if("CERTIFICATION".equals(purpose)||"AVATAR".equals(purpose))return can("boost:members:read");
        if("AFTERSALE".equals(purpose))return can("boost:aftersales:read");
        if("DELIVERY".equals(purpose))return can("boost:orders:read");
        if("LISTING".equals(purpose))return can("boost:listings:read");
        return false;
    }
}
