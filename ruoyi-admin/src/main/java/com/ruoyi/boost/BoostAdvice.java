package com.ruoyi.boost;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import static com.ruoyi.boost.BoostDb.map;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages="com.ruoyi.boost")
public class BoostAdvice {
    @ExceptionHandler(BoostException.class)
    public ResponseEntity<Object> business(BoostException e){return ResponseEntity.status(e.code).body(map("code",e.code,"msg",e.getMessage()));}
    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<Object> duplicate(DuplicateKeyException e){return ResponseEntity.status(409).body(map("code",409,"msg","请求已处理或数据重复，请刷新后查看"));}
}
