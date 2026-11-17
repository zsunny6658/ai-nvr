package com.ainvr.hub;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 云台异步应答快照的时效性判定（v1.2.1 命令发出即返回后，前端靠它区分新旧应答）。 */
class MotorReplyTest {

    @Test
    void emptySnapshotIsNeverFresh() {
        MotorReply r = MotorReply.none();
        assertFalse(r.hasReply());
        assertFalse(r.fresh());
    }

    @Test
    void replyArrivedAfterCommandIsFresh() {
        long now = System.currentTimeMillis();
        MotorReply r = new MotorReply("{\"result\":0}", now - 200L, now - 400L);
        assertTrue(r.hasReply());
        assertTrue(r.fresh(now));
    }

    @Test
    void replyBeforePreviousCommandIsNotFresh() {
        long now = System.currentTimeMillis();
        // 上一条命令的应答，晚于本次命令时间之前到达，不能当作本次应答
        MotorReply r = new MotorReply("{\"result\":0}", now - 5000L, now - 100L);
        assertFalse(r.fresh(now));
    }

    @Test
    void oldReplyOutsideWindowIsNotFresh() {
        long now = System.currentTimeMillis();
        MotorReply r = new MotorReply("{\"result\":0}", now - 30_000L, now - 31_000L);
        assertFalse(r.fresh(now));
    }

    @Test
    void commandSentButNoReplyIsPending() {
        long now = System.currentTimeMillis();
        MotorReply r = new MotorReply(null, 0L, now - 50L);
        assertFalse(r.hasReply());
        assertFalse(r.fresh(now));
    }
}
