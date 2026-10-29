package com.stocksage.debate;

import java.util.ArrayList;
import java.util.List;

/**
 * 推理面板推送闸门：开着就直接推送；关着就先缓存，release 时按顺序放出，discard 后全部丢弃。
 */
final class StreamGate {
    private final List<Runnable> pending = new ArrayList<>();
    private boolean open;
    private boolean discarded;

    StreamGate(boolean open) {
        this.open = open;
    }

    synchronized void emit(Runnable emission) {
        if (discarded) {
            return;
        }
        if (open) {
            emission.run();
        } else {
            pending.add(emission);
        }
    }

    synchronized void release() {
        if (discarded || open) {
            return;
        }
        open = true;
        pending.forEach(Runnable::run);
        pending.clear();
    }

    synchronized void discard() {
        discarded = true;
        pending.clear();
    }

    /** 重试前丢掉失败那次尚未放出的内容，前端只看到重试后的输出。 */
    synchronized void dropPending() {
        pending.clear();
    }
}
