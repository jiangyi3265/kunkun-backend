package com.ruoyi.boost;

import java.security.MessageDigest;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

public final class BoostIdempotency {
    private static final ObjectMapper JSON=new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private BoostIdempotency(){}
    public static String digest(Map<String,Object> body){
        try{
            Map<String,Object> data=new TreeMap<>(body);data.remove("requestKey");
            byte[] value=MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsBytes(data));
            StringBuilder s=new StringBuilder();for(byte b:value)s.append(String.format("%02x",b));return s.toString();
        }catch(Exception e){throw new IllegalArgumentException("Invalid request body",e);}
    }
    public static void same(Map<String,Object> row,String hash){
        if(!hash.equals(BoostDb.str(row,"requestHash")))throw new BoostException(409,"此请求编号已提交其他内容，请先核对原操作结果");
    }
}
