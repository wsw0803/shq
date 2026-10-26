package com.tencent.tim.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 每个游戏「怎么把 token 送进去」的策略表。
 *
 * 每个游戏「怎么把 token 送进去」的策略表。
 *
 * 两个能力位：
 *   external  —— 支持外部投递：找到游戏的 auth 回调组件，startActivity 塞结果
 *   inProcess —— 必须进程内注入：Xposed 在游戏进程里接管
 *
 * 教训：三角洲一开始被我标成 inProcess=true，理由是 GameLaunch 注释里
 * 「WebViewActivity 拿不到 url 就 destroySurface → 黑屏」。
 *
 * 但那条注释描述的是「找不到回调组件、退而启动内部页」时的表现。
 * 实机日志显示三角洲有真实回传通道：
 *
 *   getCallingActivity = com.tencent.tmgp.dfm/com.tencent.connect.common.AssistActivity
 *   AssistActivity: onCreate → onResume → onActivityResult → onDestroy
 *   checkFinished=true，ACTIVITY_RESULT 正常回调
 *
 * 这和黑屏那条不是同一条路。老八的做法就是扫码后 setResult 交回，
 * 让游戏自己走 MSDK 校验，不往游戏里塞 token。
 * 标成 inProcess 等于把唯一可行的路堵死 —— 三角洲因此一直登录不了。
 *
 * 现在所有游戏都走外部投递。inProcess 位保留，但目前没有游戏需要它。
 */
public final class GamePlan {

    public final String game;
    public final String pkg;
    /** 支持外部投递（GameLaunch 那条路）。 */
    public final boolean external;
    /** 必须进程内注入（Xposed），外部投递无效。 */
    public final boolean inProcess;
    /** 人话备注，直接进日志和界面。 */
    public final String note;

    private static final Map<String, GamePlan> BY_GAME = new LinkedHashMap<String, GamePlan>();
    private static final Map<String, GamePlan> BY_PKG = new LinkedHashMap<String, GamePlan>();

    private static void add(GamePlan p) {
        BY_GAME.put(p.game, p);
        BY_PKG.put(p.pkg, p);
    }

    static {
        add(new GamePlan("和平", "com.tencent.tmgp.pubgmhd", true, false,
                "外部投递可用"));
        add(new GamePlan("王者", "com.tencent.tmgp.sgame", true, false,
                "外部投递可用"));
        // 三角洲必须允许外部投递。
        //
        // 之前标成 external=false 是错的。当时的依据是 GameLaunch 注释里
        // 记录的「WebViewActivity 拿不到 url 就 destroySurface → 黑屏」，
        // 但那是「找不到回调组件、退而启动内部页」时的表现。
        //
        // 实机日志显示三角洲有真实回传通道：
        //   getCallingActivity = com.tencent.tmgp.dfm/com.tencent.connect.common.AssistActivity
        //   AssistActivity 走完 onCreate → onResume → onActivityResult → onDestroy
        //   checkFinished=true，ACTIVITY_RESULT 正常回调
        //
        // 这条路和黑屏那条不是同一条。老八也是扫码后 setResult 交回，
        // 让游戏自己走 MSDK 校验，不往游戏里塞 token。
        // 标成 external=false 等于把唯一可行的路堵死。
        add(new GamePlan("三角洲", "com.tencent.tmgp.dfm", true, false,
                "扫码后 setResult 交回 AssistActivity"));
        add(new GamePlan("暗区突围", "com.tencent.mf.uam", true, false,
                "外部投递可用"));
        add(new GamePlan("无畏契约", "com.tencent.tmgp.codev", true, false,
                "外部投递可用"));
    }

    private GamePlan(String game, String pkg, boolean external, boolean inProcess, String note) {
        this.game = game;
        this.pkg = pkg;
        this.external = external;
        this.inProcess = inProcess;
        this.note = note;
    }

    public static GamePlan ofGame(String game) {
        return game == null ? null : BY_GAME.get(game.trim());
    }

    public static GamePlan ofPackage(String pkg) {
        return pkg == null ? null : BY_PKG.get(pkg.trim());
    }

    /** 该游戏能否用外部投递。三角洲返回 false。 */
    public static boolean canDispatchExternally(String game) {
        GamePlan p = ofGame(game);
        return p != null && p.external;
    }

    /** 该游戏是否必须 Xposed。 */
    public static boolean needsInProcess(String game) {
        GamePlan p = ofGame(game);
        return p != null && p.inProcess;
    }
}
