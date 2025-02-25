package org.bf2.systemtest.framework;

import io.fabric8.kubernetes.client.utils.Serialization;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaAgentStatus;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaCondition;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaStatus;
import org.bf2.systemtest.framework.resource.ManagedKafkaResourceType;
import org.bf2.test.k8s.KubeClient;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AssertUtils {

    public static void assertManagedKafka(ManagedKafka mk) {
        KubeClient kube = KubeClient.getInstance();

        assertNotNull(ManagedKafkaResourceType.getOperation().inNamespace(mk.getMetadata().getNamespace()).withName(mk.getMetadata().getName()).get());
        assertTrue(kube.client().pods().inNamespace(mk.getMetadata().getNamespace()).list().getItems().size() > 0);
        assertEquals("Running", ManagedKafkaResourceType.getCanaryPod(mk).getStatus().getPhase());
        assertEquals("Running", ManagedKafkaResourceType.getAdminApiPod(mk).getStatus().getPhase());
        assertEquals(3, ManagedKafkaResourceType.getKafkaPods(mk).size());
        if (!ManagedKafkaResourceType.isDevKafka(mk)) {
            assertEquals(1, ManagedKafkaResourceType.getKafkaExporterPods(mk).size());
        }
        assertEquals(3, ManagedKafkaResourceType.getZookeeperPods(mk).size());
    }

    public static void assertManagedKafkaStatus(ManagedKafka mk, ManagedKafkaStatus mkStatus) {
        assertEquals(mk.getStatus().getAdminServerURI(), mkStatus.getAdminServerURI());
        assertEquals(mk.getStatus().getCapacity().getTotalMaxConnections(), mkStatus.getCapacity().getTotalMaxConnections());
        assertEquals(mk.getStatus().getCapacity().getIngressEgressThroughputPerSec(), mkStatus.getCapacity().getIngressEgressThroughputPerSec());
        assertEquals(mk.getStatus().getCapacity().getMaxDataRetentionPeriod(), mkStatus.getCapacity().getMaxDataRetentionPeriod());
        assertEquals(mk.getStatus().getCapacity().getMaxPartitions(), mkStatus.getCapacity().getMaxPartitions());
        assertEquals(mk.getStatus().getCapacity().getMaxDataRetentionSize(), mkStatus.getCapacity().getMaxDataRetentionSize());
        mkStatus.getConditions()
                .forEach(condition -> assertTrue(ManagedKafkaResourceType.hasConditionStatus(mk,
                        ManagedKafkaCondition.Type.valueOf(condition.getType()),
                        ManagedKafkaCondition.Status.valueOf(condition.getStatus()))));
    }

    public static void assertManagedKafkaAgentStatus(ManagedKafkaAgentStatus agentStatus) {
        String yml = Serialization.asYaml(agentStatus);
        assertEquals(1, agentStatus.getConditions().size(), yml);
        assertNotNull(agentStatus.getTotal(), yml);
        assertNotNull(agentStatus.getRemaining(), yml);
        assertNotNull(agentStatus.getResizeInfo(), yml);
        assertNotNull(agentStatus.getNodeInfo(), yml);
    }

    public static void assertStrimziUpgraded(ManagedKafka mk, LocalDateTime dateBeforeStartUpgrade) {
        ManagedKafkaResourceType.getKafkaPods(mk).forEach(kp -> {
            LocalDateTime started = LocalDateTime.ofInstant(Instant.parse(kp.getStatus().getStartTime()), ZoneOffset.UTC);
            assertTrue(dateBeforeStartUpgrade.isBefore(started));
        });

        ManagedKafkaResourceType.getZookeeperPods(mk).forEach(zp -> {
            LocalDateTime started = LocalDateTime.ofInstant(Instant.parse(zp.getStatus().getStartTime()), ZoneOffset.UTC);
            assertTrue(dateBeforeStartUpgrade.isBefore(started));
        });
    }
}
