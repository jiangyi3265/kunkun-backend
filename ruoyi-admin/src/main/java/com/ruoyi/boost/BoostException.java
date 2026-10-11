package com.ruoyi.boost;

public class BoostException extends RuntimeException {
    public final int code;
    public BoostException(int code, String message) { super(message); this.code = code; }
    public static void require(boolean condition, String message) {
        if (!condition) throw new BoostException(400, message);
    }
}
