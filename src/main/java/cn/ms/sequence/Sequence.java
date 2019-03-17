package cn.ms.sequence;

/**
 * 基于Twitter的Snowflake算法实现分布式高效有序ID生产黑科技(sequence)
 *
 * <br>
 * SnowFlake的结构如下(每部分用-分开):<br>
 * <br>
 * 0 - 0000000000 0000000000 0000000000 0000000000 0 - 00000 - 00000 - 000000000000 <br>
 * <br>
 * 1位标识，由于long基本类型在Java中是带符号的，最高位是符号位，正数是0，负数是1，所以id一般是正数，最高位是0<br>
 * <br>
 * 41位时间截(毫秒级)，注意，41位时间截不是存储当前时间的时间截，而是存储时间截的差值（当前时间截 - 开始时间截)
 * 得到的值），这里的的开始时间截，一般是我们的id生成器开始使用的时间，由我们程序来指定的（如下下面程序IdWorker类的startTime属性）。41位的时间截，可以使用69年，年T = (1L << 41) / (1000L * 60 * 60 * 24 * 365) = 69<br>
 * <br>
 * 10位的数据机器位，可以部署在1024个节点，包括5位datacenterId和5位workerId<br>
 * <br>
 * 12位序列，毫秒内的计数，12位的计数顺序号支持每个节点每毫秒(同一机器，同一时间截)产生4096个ID序号<br>
 * <br>
 * <br>
 * 加起来刚好64位，为一个Long型。<br>
 * SnowFlake的优点是，整体上按照时间自增排序，并且整个分布式系统内不会产生ID碰撞(由数据中心ID和机器ID作区分)，并且效率较高，经测试，SnowFlake每秒能够产生26万ID左右。
 *
 * @author lry
 * @version 3.0
 */
public class Sequence {

    /**
     * 起始时间戳
     **/
    private final static long START_TIME = 1552786187353L;

    /**
     * workerId占用的位数5（表示只允许workId的范围为：0-1023）
     **/
    private final static long WORKER_ID_BITS = 5L;
    /**
     * dataCenterId占用的位数：5
     **/
    private final static long DATA_CENTER_ID_BITS = 5L;
    /**
     * 序列号占用的位数：12（表示只允许workId的范围为：0-4095）
     **/
    private final static long SEQUENCE_BITS = 12L;

    /**
     * workerId可以使用的最大数值：31
     **/
    private final static long MAX_WORKER_ID = ~(-1L << WORKER_ID_BITS);
    /**
     * dataCenterId可以使用的最大数值：31
     **/
    private final static long MAX_DATA_CENTER_ID = ~(-1L << DATA_CENTER_ID_BITS);

    private final static long WORKER_ID_SHIFT = SEQUENCE_BITS;
    private final static long DATA_CENTER_ID_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS;
    private final static long TIMESTAMP_LEFT_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS + DATA_CENTER_ID_BITS;

    /**
     * 用mask防止溢出:位与运算保证计算的结果范围始终是 0-4095
     **/
    private final static long SEQUENCE_MASK = ~(-1L << SEQUENCE_BITS);

    private long workerId;
    private long dataCenterId;
    private long sequence = 0L;
    private long lastTimestamp = -1L;
    /**
     * 允许时间回拨的毫秒量
     */
    private long timeOffset;
    /**
     * 是否使用定时任务解决高性能获取时间戳的问题
     */
    private boolean clock;

    /**
     * 简单分布式ID（不解决高并发获取时间戳的问题）
     *
     * @param workerId     工作机器ID,数据范围为0~31
     * @param dataCenterId 数据中心ID,数据范围为0~31
     */
    public Sequence(long workerId, long dataCenterId) {
        this(workerId, dataCenterId, false, 5L);
    }

    /**
     * 基于Snowflake创建分布式ID生成器
     *
     * @param workerId     工作机器ID,数据范围为0~31
     * @param dataCenterId 数据中心ID,数据范围为0~31
     * @param clock        true表示解决高并发下获取时间戳的性能问题
     * @param timeOffset   允许时间回拨的毫秒量,建议5ms
     */
    public Sequence(long workerId, long dataCenterId, boolean clock, long timeOffset) {
        if (workerId > MAX_WORKER_ID || workerId < 0) {
            throw new IllegalArgumentException("Worker Id can't be greater than " + MAX_WORKER_ID + " or less than 0");
        }
        if (dataCenterId > MAX_DATA_CENTER_ID || dataCenterId < 0) {
            throw new IllegalArgumentException("Data Center Id can't be greater than " + MAX_DATA_CENTER_ID + " or less than 0");
        }

        this.workerId = workerId;
        this.dataCenterId = dataCenterId;
        this.clock = clock;
        this.timeOffset = timeOffset;
    }

    /**
     * 获取ID
     *
     * @return long
     */
    public synchronized Long nextId() {
        long timestamp = this.timeGen();

        // 闰秒：如果当前时间小于上一次ID生成的时间戳，说明系统时钟回退过，这个时候应当抛出异常
        if (timestamp < lastTimestamp) {
            long offset = lastTimestamp - timestamp;
            if (offset > timeOffset) {
                throw new RuntimeException("Clock moved backwards, refusing to generate id for [" + offset + "ms]");
            }

            try {
                // 时间回退timeOffset毫秒内，则允许等待2倍的偏移量后重新获取，解决小范围的时间回拨问题
                this.wait(offset << 1);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            timestamp = this.timeGen();
            if (timestamp < lastTimestamp) {
                throw new RuntimeException("Clock moved backwards, refusing to generate id for [" + offset + "ms]");
            }
        }

        // 同一毫秒内序列直接自增
        if (lastTimestamp == timestamp) {
            // 通过位与运算保证计算的结果范围始终是 0-4095
            sequence = (sequence + 1) & SEQUENCE_MASK;
            if (sequence == 0) {
                timestamp = this.tilNextMillis(lastTimestamp);
            }
        } else {
            // 需要解决：跨毫秒生成ID序列号第1个每次都为0的情况
            sequence = 0L;
        }

        lastTimestamp = timestamp;

        /*
         * 1.左移运算是为了将数值移动到对应的段(41、5、5，12那段因为本来就在最右，因此不用左移)
         * 2.然后对每个左移后的值(la、lb、lc、sequence)做位或运算，是为了把各个短的数据合并起来，合并成一个二进制数
         * 3.最后转换成10进制，就是最终生成的id
         */
        return ((timestamp - START_TIME) << TIMESTAMP_LEFT_SHIFT) |
                (dataCenterId << DATA_CENTER_ID_SHIFT) |
                (workerId << WORKER_ID_SHIFT) |
                sequence;
    }

    public static void main(String[] args) {
//        Sequence sequence = new Sequence(1, 1);
//        for (int i = 0; i < 100; i++) {
//            System.out.println(sequence.nextId());
//        }
        System.out.println(System.currentTimeMillis());
    }

    /**
     * 保证返回的毫秒数在参数之后(阻塞到下一个毫秒，直到获得新的时间戳)
     *
     * @param lastTimestamp last timestamp
     * @return next millis
     */
    private long tilNextMillis(long lastTimestamp) {
        long timestamp = this.timeGen();
        while (timestamp <= lastTimestamp) {
            // 如果发现时间回拨，则自动重新获取（可能会处于无限循环中）
            timestamp = this.timeGen();
        }

        return timestamp;
    }

    /**
     * 获得系统当前毫秒时间戳
     *
     * @return timestamp 毫秒时间戳
     */
    private long timeGen() {
        return clock ? SystemClock.INSTANCE.currentTimeMillis() : System.currentTimeMillis();
    }

}