package com.ruoyi.boost;

import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class BoostInput {
    private BoostInput() {}
    public static String text(Map<String,Object> body,String key,int min,int max) {
        Object raw=body.get(key);
        String v=raw==null?"":raw.toString().trim();
        BoostException.require(v.length()>=min && v.length()<=max,key+" 请填写 "+min+"～"+max+" 个字符");
        return v;
    }
    public static long number(Map<String,Object> body,String key,long min,long max) {
        try {
            String value=String.valueOf(body.get(key));
            long n=Long.parseLong(value);
            BoostException.require(n>=min&&n<=max,key+" 超出允许范围"); return n;
        } catch (NumberFormatException e) { throw new BoostException(400,key+" 必须是有效整数"); }
    }
    public static String choice(Map<String,Object> body,String key,String... allowed) {
        String s=text(body,key,1,100);
        BoostException.require(Arrays.asList(allowed).contains(s),key+" 选项无效"); return s;
    }
    public static String images(Map<String,Object> b) {
        Object raw=b.get("images");
        if(raw==null) return "[]";
        BoostException.require(raw instanceof List,"图片格式不正确");
        List<?> values=(List<?>)raw;
        BoostException.require(values.size()<=9,"最多上传 9 张图片");
        for(Object v:values) BoostException.require(v instanceof String && v.toString().matches("/boost-media/[a-f0-9]{32}\\.(png|jpg|webp)"),"请先上传图片");
        try { return new ObjectMapper().writeValueAsString(values); } catch(Exception e) { throw new BoostException(400,"图片格式错误"); }
    }
    public static int page(Map<String,String> q) { try { return Math.max(1,Math.min(10000,Integer.parseInt(q.getOrDefault("page","1")))); }catch(Exception e){return 1;} }
}
