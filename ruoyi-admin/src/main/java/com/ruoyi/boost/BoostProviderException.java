package com.ruoyi.boost;

/** Only a verified provider response can establish that a remote order does not exist. */
public class BoostProviderException extends BoostException {
    public final String providerCode;
    public final int httpStatus;
    public final boolean verified;
    public BoostProviderException(int status,String providerCode,boolean verified){
        super(503,"支付渠道暂未完成请求（"+providerCode+"），请保留原单号重试");
        this.httpStatus=status;this.providerCode=providerCode;this.verified=verified;
    }
    public boolean missing(){return verified&&httpStatus==404&&java.util.Arrays.asList("ORDER_NOT_EXIST","RESOURCE_NOT_EXISTS","REFUND_NOT_EXIST","NOT_FOUND").contains(providerCode);}
}
