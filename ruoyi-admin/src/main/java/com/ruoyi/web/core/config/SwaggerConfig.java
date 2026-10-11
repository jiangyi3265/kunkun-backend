package com.ruoyi.web.core.config;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.*;
@Configuration public class SwaggerConfig {
 @Bean public OpenAPI api(){return new OpenAPI().info(new Info().title("代练集市 API").version("1.0"));}
}
