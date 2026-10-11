package com.ruoyi.boost;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import com.ruoyi.common.core.domain.entity.SysMenu;
import com.ruoyi.common.constant.Constants;

/** Return only routes shipped in this admin build. Leave shared DB menus untouched. */
public final class BoostMenuScope {
    private static final Set<String> COMPONENTS=new HashSet<>();
    static {try(BufferedReader r=new BufferedReader(new InputStreamReader(Objects.requireNonNull(BoostMenuScope.class.getResourceAsStream("/boost/admin-views.txt")),StandardCharsets.UTF_8))){String s;while((s=r.readLine())!=null)if(!s.trim().isEmpty())COMPONENTS.add(s.trim());}catch(IOException e){throw new ExceptionInInitializerError(e);}}
    private BoostMenuScope(){}
    public static List<SysMenu> filter(List<SysMenu> menus){
        return filter(menus, Collections.singleton(Constants.ALL_PERMISSION));
    }
    public static List<SysMenu> filter(List<SysMenu> menus, Set<String> permissions){
        Set<String> grants=permissions==null?Collections.emptySet():permissions;
        List<SysMenu> result=new ArrayList<>();
        for(SysMenu m:menus){
            if("M".equals(m.getMenuType())){m.setChildren(filter(m.getChildren(),grants));if(!m.getChildren().isEmpty())result.add(m);}
            else if("C".equals(m.getMenuType())&&COMPONENTS.contains(m.getComponent())&&!Arrays.asList("monitor/job/index","monitor/druid/index","tool/gen/index","tool/swagger/index").contains(m.getComponent())&&allowed(m,grants))result.add(m);
        }return result;
    }
    private static boolean allowed(SysMenu menu,Set<String> permissions){
        if(permissions.contains(Constants.ALL_PERMISSION))return true;
        String required=menu.getPerms();
        if(required==null||required.trim().isEmpty())return true;
        return Arrays.stream(required.split(",")).map(String::trim).anyMatch(permissions::contains);
    }
}
