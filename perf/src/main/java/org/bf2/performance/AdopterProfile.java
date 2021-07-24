package org.bf2.performance;

import org.bf2.operator.operands.KafkaInstanceConfiguration;

public class AdopterProfile {

    public static final KafkaInstanceConfiguration VALUE_PROD = buildProfile(
            "4Gi", "1G", "1000m",
            "8Gi", "3G", "3000m");

    public static final KafkaInstanceConfiguration M5_XLARGE = buildProfile(
            "4Gi", "1G", "1000m",
            "11Gi", "3G", "2500m");

    public static final KafkaInstanceConfiguration SMALL_VALUE_PROD = buildProfile(
            "1Gi", "500M", "500m",
            "1Gi", "500M", "1000m");

    public static final KafkaInstanceConfiguration TYPE_KICKER = buildProfile(
            "2Gi", "1G", "500m",
            "2Gi", "1G", "500m");

    public static KafkaInstanceConfiguration buildProfile(String zookeeperContainerMemory, String zookeeperJavaMemory,
            String zookeeperCpu, String kafkaContainerMemory, String kafkaJavaMemory, String kafkaCpu) {
        KafkaInstanceConfiguration config = new KafkaInstanceConfiguration();
        config.getKafka().setContainerMemory(kafkaContainerMemory);
        config.getKafka().setContainerCpu(kafkaCpu);
        config.getKafka().setJvmXms(kafkaJavaMemory);
        config.getKafka().setEnableQuota(false);
        config.getZookeeper().setContainerCpu(zookeeperCpu);
        config.getZookeeper().setContainerMemory(zookeeperContainerMemory);
        config.getZookeeper().setJvmXms(zookeeperJavaMemory);
        return config;
    }
}
