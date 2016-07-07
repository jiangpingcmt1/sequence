#sequence

##简介

基于Snowflake实现64位自增ID算法。

Twitter-Snowflake算法产生的背景相当简单，为了满足Twitter每秒上万条消息的请求，每条消息都必须分配一条唯一的id，这些id还需要一些大致的顺序（方便客户端排序），并且在分布式系统中不同机器产生的id必须不同。

##Snowflake算法核心
把时间戳，工作机器id，序列号组合在一起。

![Snowflake算法核心](docs/snowflake-64bit.jpg)

