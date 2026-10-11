package com.ruoyi.boost;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import com.ruoyi.common.utils.SecurityUtils;
import static com.ruoyi.boost.BoostController.ok;
@RestController public class BoostBenefitsController {
 private final BoostBenefits benefits;
 public BoostBenefitsController(BoostBenefits b){benefits=b;}
 @GetMapping("/boost-admin/benefits/rules") @PreAuthorize("@boostAccess.can('boost:members:read')") public Object rules(){return ok(benefits.rules());}
 @PutMapping("/boost-admin/benefits/rules") @PreAuthorize("@boostAccess.can('boost:benefits:rules')") public Object rules(@RequestBody Map<String,Object>b){return ok(benefits.rules(SecurityUtils.getUserId(),b));}
 @PostMapping("/boost-admin/benefits/{id}") @PreAuthorize("@boostAccess.can('boost:benefits:manage')") public Object update(@PathVariable long id,@RequestBody Map<String,Object>b){return ok(benefits.update(SecurityUtils.getUserId(),id,b));}
}
