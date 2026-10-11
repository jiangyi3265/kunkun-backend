package com.ruoyi.boost;

import java.util.*;
import static com.ruoyi.boost.BoostDb.*;

/** SQL fragments are application constants; user input is always bound separately. */
public final class BoostPages {
    private BoostPages(){}
    public static Map<String,Object> query(BoostDb db,Map<String,String> q,String select,String from,String order,Object... args){
        int page=1;try{page=Integer.parseInt(q.getOrDefault("page","1"));}catch(NumberFormatException e){throw new BoostException(400,"页码无效");}
        BoostException.require(page>=1&&page<=1000000,"页码超出范围");int size=20;
        List<Object> params=new ArrayList<>(Arrays.asList(args));params.add(size);params.add((page-1)*size);
        long total=db.count("SELECT COUNT(*) "+from,args);
        return map("items",db.list(select+" "+from+" ORDER BY "+order+" LIMIT ? OFFSET ?",params.toArray()),"total",total,"page",page,"hasMore",(long)page*size<total);
    }
}
