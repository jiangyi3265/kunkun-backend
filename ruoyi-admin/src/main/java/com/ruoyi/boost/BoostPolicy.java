package com.ruoyi.boost;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import static com.ruoyi.boost.BoostDb.*;

@Component
public class BoostPolicy {
    @Value("${boost.policy.operator:}") private String operator;
    @Value("${boost.policy.contact:}") private String contact;
    @Value("${boost.policy.retention:}") private String retention;
    @Value("${boost.policy.version:development}") private String version;
    @Value("${boost.policy.effective-date:}") private String effectiveDate;
    public Map<String,Object> document(){
        return map("operator",operator,"contact",contact,"retention",retention,"version",version,"effectiveDate",effectiveDate,"ready",!operator.isEmpty()&&!contact.isEmpty()&&!retention.isEmpty()&&!"development".equals(version)&&!effectiveDate.isEmpty(),
            "sections",Arrays.asList(
                map("title","道具发布与认证","text","仅支持和平精英地铁逃生游戏道具交易，禁止出售游戏账号。求购发布者须通过真实实名认证；出售者还须通过店铺认证并验证手机号。新发布明确道具介绍、单价、数量和详情图片，旧整批交易保留原约定。店铺材料仅可首次提交，后续由有权限的后台核验更正并保留历史；手机号不公开展示，短信验证不能替代实名认证。店铺截图材料不是实名认证；勿在商品、聊天或店铺材料上传身份证、游戏密码或银行卡密码。"),
                map("title","下单、交付与结算","text","求购用于玩家协商，不自动接单；协商后从卖家在售道具下单。每个商品按整批数量交易。付款以服务端确认的渠道结果为准；卖家提交游戏内交付凭证，买家核对实际收到的道具后确认收货并结算。支付未开放时只能保留或取消待付款订单，不支持交付、结算或退款资金操作。"),
                map("title","取消与售后","text","未付款订单可取消，到期经核对未付款后关闭。验收前可通过订单申请一次全额售后；审核期间暂停交付。退款以支付渠道确认结果为准，异常由平台跟进。提现审核不通过或渠道转账失败后，申请金额返回可用余额。"),
                map("title","积分活动与兑换","text","可用积分与等级成长值分别记录。积分仅免费发放，不可购买、转让、提现或兑换现金。抽奖规则未确认前不开放，开放时显示当前可领取奖品与概率；五连逐次更新库存，失败整单回滚。勋章领取后公开展示在个人主页；兑换实物后填写收货信息，由后台记录发货，收到实物后确认收货。"),
                map("title","数据使用与访问","text","账号资料、微信身份标识、订单、聊天、图片和必要的安全日志用于服务履行、收付款、账号安全及争议处理。个人主页公开昵称、头像、个人简介、认证状态、会员等级、成长值、IP省份、已领取活动勋章以及审核通过的交易发布；请勿将私密联系方式或身份证等信息写入公开简介。IP属地为网络位置近似值，不是身份认证，无法识别时显示未知；该资料字段只保存省份，离线查询不向定位数据供应者发送用户IP。私人聊天与交付、认证、售后材料按业务参与关系或运营职责授权访问。兑换实物的收货人、手机号、详细地址及物流单号仅供本人和有兑换发货权限的运营人员用于寄送与售后，其他用户及仅有商品管理权限的人员不能访问。微信收付款信息仅用于相应渠道交易。"),
                map("title","数据保存与联系","text","运营主体："+operator+"。数据保存规则："+retention+"。个人信息查询、更正、删除申请及争议联系："+contact+"。账号退出清除当前设备令牌；修改密码会撤销已有会话。法定或交易争议保留需求须由运营核对处理。")));
    }
}
