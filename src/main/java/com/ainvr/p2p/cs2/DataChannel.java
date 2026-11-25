package com.ainvr.p2p.cs2;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * CS2 通道重组器（1:1 对照 go2rtc dataChannel）。
 *
 * <ul>
 *   <li>流内按 <b>BE32 长度前缀</b>切分逻辑消息（一个网络包可含多条消息）；</li>
 *   <li>UDP 下按 BE16 seq 做乱序缓存（通道 2 缓存 250），连续 seq 依次交付；</li>
 *   <li>通道 0 关闭乱序缓存（pushSize=0），pop 队列容量 10；通道 2 缓存 250、队列 100。</li>
 * </ul>
 */
public final class DataChannel {

    /**
     * 单条逻辑消息长度上限（BE32 长度前缀上限）。视频关键帧实际约 0.5~2MB，留足余量；
     * 超过视为流失步（垃圾字节被当成消息长度），必须立刻断流重连——否则
     * {@link #append} 会把后续所有包攒进一个无限增长的缓冲，直至 Java 堆 OOM
     * （2026-11-25 真机凌晨 0:52 三台设备同时 OOM 的根因）。
     */
    private static final int MAX_MSG_SIZE = 16 * 1024 * 1024;

    private final int pushSize;
    private final Map<Integer, byte[]> pushBuf;
    private final BlockingQueue<byte[]> popBuf;
    private volatile boolean closed;

    private int waitSeq;
    private byte[] waitData = new byte[0];
    private int waitLen;
    private int waitSize;

    public DataChannel(int pushSize, int popSize) {
        this.pushSize = pushSize;
        this.pushBuf = pushSize > 0 ? new HashMap<>(pushSize) : null;
        this.popBuf = new ArrayBlockingQueue<>(Math.max(1, popSize));
    }

    /**
     * TCP（天然有序）或连续 seq 数据入站：追加并按 BE32 长度前缀切分投递。
     */
    public synchronized void push(byte[] data) throws IOException {
        append(data, data.length);
        while (waitLen > 4) {
            if (waitSize == 0) {
                waitSize = readBe32(waitData, 0);
                if (waitSize > MAX_MSG_SIZE) {
                    // 流失步防护：垃圾长度前缀。抛错让连接重建（重连后重新同步），
                    // 而不是带着污染的重组缓冲继续攒包到堆爆。
                    throw new IOException("cs2: 消息长度前缀异常 " + waitSize + "（流失步，需重连）");
                }
                consume(4);
            }
            if (waitSize > waitLen) {
                break;
            }
            byte[] msg = Arrays.copyOf(waitData, waitSize);
            if (!popBuf.offer(msg)) {
                throw new IOException("cs2: pop buffer is full");
            }
            consume(waitSize);
            waitSize = 0;
        }
    }

    /**
     * UDP 数据入站（带 seq）。
     *
     * @return 本次连续交付的消息数；0=已缓存或重复旧包；-1=缓存禁用/已满无法保存
     */
    public synchronized int pushSeq(int seq, byte[] data) throws IOException {
        int s = seq & 0xFFFF;
        int diff = (short) ((s - (waitSeq & 0xFFFF)) & 0xFFFF);
        if (diff > 0) {
            if (pushBuf == null) {
                return -1;
            }
            if (!pushBuf.containsKey(s)) {
                if (pushBuf.size() == pushSize) {
                    return -1;
                }
                pushBuf.put(s, data.clone());
            }
            return 0;
        }
        if (diff < 0) {
            return 0;
        }
        int processed = 1;
        byte[] current = data;
        while (true) {
            push(current);
            waitSeq = (waitSeq + 1) & 0xFFFF;
            current = pushBuf == null ? null : pushBuf.get(waitSeq);
            if (current != null) {
                pushBuf.remove(waitSeq);
                processed++;
            } else {
                return processed;
            }
        }
    }

    /**
     * 阻塞取下一条逻辑消息；通道关闭且排空后返回 null。
     */
    public byte[] pop() throws InterruptedException {
        while (true) {
            byte[] data = popBuf.poll(200, TimeUnit.MILLISECONDS);
            if (data != null) {
                return data;
            }
            if (closed) {
                return popBuf.poll();
            }
        }
    }

    /**
     * 在指定超时内取下一条消息；超时或通道关闭且排空后返回 null（调用方据此区分超时/错误）。
     */
    public byte[] pop(long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            long remain = deadline - System.currentTimeMillis();
            if (remain <= 0) {
                return null;
            }
            byte[] data = popBuf.poll(Math.min(200, Math.max(1, remain)), TimeUnit.MILLISECONDS);
            if (data != null) {
                return data;
            }
            if (closed) {
                return popBuf.poll();
            }
        }
    }

    public synchronized void close() {
        closed = true;
    }

    private void append(byte[] src, int len) throws IOException {
        if (waitLen + len > MAX_MSG_SIZE) {
            throw new IOException("cs2: 重组缓冲超过 " + MAX_MSG_SIZE + " 字节（流失步，需重连）");
        }
        byte[] grown = new byte[waitLen + len];
        System.arraycopy(waitData, 0, grown, 0, waitLen);
        System.arraycopy(src, 0, grown, waitLen, len);
        waitData = grown;
        waitLen += len;
    }

    private void consume(int n) {
        int rem = waitLen - n;
        if (rem <= 0) {
            waitData = new byte[0];
            waitLen = 0;
        } else {
            byte[] moved = new byte[rem];
            System.arraycopy(waitData, n, moved, 0, rem);
            waitData = moved;
            waitLen = rem;
        }
    }

    private static int readBe32(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }
}
