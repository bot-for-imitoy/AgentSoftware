package com.maf.scheduler.core;

import java.util.HashMap;
import java.util.Map;

/**
 * 角色中文名 → 汉语拼音 (容器系统用户名) 映射表 (Python 版 pinyin_map.py).
 *
 * 规则: 全拼小写无空格, 姓名连写 (郭晓东 → guoxiaodong).
 */
public final class PinyinMap {

    private PinyinMap() {
    }

    /** 角色名 → 拼音 (48 个默认模板角色; 新角色未收录时回退用 role_id). */
    public static final Map<String, String> NAME_PINYIN = new HashMap<>();

    static {
        put("林总", "linzong");        // CEO
        put("陈总", "chenzong");       // COO
        put("王人事", "wangrenshi");   // HR
        put("钱财", "qiancai");        // CFO
        put("李明", "liming");         // fullstack_dev (模板)
        put("王建国", "wangjianguo");  // architect
        put("张伟", "zhangwei");       // reviewer
        put("刘洋", "liuyang");        // qa_engineer
        put("赵强", "zhaoqiang");      // ops_engineer
        put("陈静", "chenjing");       // content_marketer
        put("孙晓", "sunxiao");        // data_analyst
        put("周梅", "zhoumei");        // support_agent
        put("顾承宇", "guchengyu");    // frontend_dev_1
        put("唐思远", "tangsiyuan");   // frontend_dev_2
        put("罗子涵", "luozihan");     // frontend_dev_3
        put("彭志强", "pengzhiqiang"); // backend_dev_1
        put("萧文博", "xiaowenbo");    // backend_dev_2
        put("邓立群", "dengliqun");    // backend_dev_3
        put("曾子墨", "zengzimo");     // mobile_dev_1
        put("卢俊豪", "lujunhao");     // mobile_dev_2
        put("蔡文静", "caiwenjing");   // mobile_dev_3
        put("谭志远", "tanzhiyuan");   // fullstack_dev_1
        put("范晓峰", "fanxiaofeng");  // fullstack_dev_2
        put("高梦洁", "gaomengjie");   // fullstack_dev_3
        put("郭晓东", "guoxiaodong");  // tester_1
        put("马春燕", "machunyan");    // tester_2
        put("宋佳琪", "songjiaqi");    // tester_3
        put("袁明轩", "yuanmingxuan"); // tester_4
        put("胡婷婷", "hutingting");   // tester_5
        put("石景山", "shijingshan");  // tester_6
        put("程雪梅", "chengxuemei");  // tester_7
        put("陆一帆", "luyifan");      // tester_8
        put("孟浩然", "menghaoran");   // tester_9
        put("沈佳宜", "shenjiayi");    // tester_10
        put("田晓慧", "tianxiaohui");  // tester_11
        put("魏莱", "weilai");         // tester_12
        put("姜文博", "jiangwenbo");   // tester_13
        put("谢婉婷", "xiewanting");   // tester_14
        put("邹明", "zouming");        // tester_15
        put("苏韵", "suyun");          // tester_16
        put("潘志远", "panzhiyuan");   // tester_17
        put("葛天宇", "getianyu");     // tester_18
        put("薛静怡", "xuejingyi");    // tester_19
        put("阮志明", "ruanzhiming");  // tester_20
        put("白鹏", "baipeng");        // attacker_1
        put("严冬", "yandong");        // attacker_2
        put("纪安", "jian");           // attacker_3
        put("方谨言", "fangjinyan");   // release_manager
        put("高远", "gaoyuan");        // CTO
        put("陈思远", "chensiyuan");   // frontend_lead
        put("王宇轩", "wangyuxuan");   // backend_lead
        put("李俊杰", "lijunjie");     // fullstack_lead
        put("张雅婷", "zhangyating");  // mobile_lead
        put("刘子涵", "liuzihan");     // test_lead
    }

    private static void put(String name, String pinyin) {
        NAME_PINYIN.put(name, pinyin);
    }

    /** 查表, 未收录回退 fallback (ASCII 安全). */
    public static String toPinyin(String name, String fallback) {
        String p = NAME_PINYIN.get(name);
        return p != null ? p : fallback;
    }
}
