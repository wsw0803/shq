package com.tencent.tim.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 每个游戏「怎么把 token 送进去」的策略表。
 *
 * 重构要点：原实现只有 GameLaunch 一条路——找到游戏的 auth 回调组件，
 * startActivity 把结果塞过去。对和平精英这类「真的在 startActivityForResult
 * 等我们」的游戏可行，对三角洲不可行：
 *
 *   三角洲 com.tencent.tmgp.dfm 的登录在 MSDK 进程内完成校验，
 *   pixui.WebViewActivity 拿不到 Intent 里的 url 就直接 destroySurface，
 *   留下空任务栈 = 黑屏。它没有「外部投递入口」。
 *
 * 所以这里把「外部投递」和「进程内注入」拆成两个正交能力位，
 * 由策略表决定，而不是靠 GameLaunch 里的候选排序去猜。
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
        add(new GamePlan("三角洲", "com.tencent.tmgp.dfm", false, true,
                "MSDK 进程内校验，外部投递必定黑屏"));
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
